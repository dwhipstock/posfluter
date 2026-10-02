package dev.dwhipstock.pos.payments

import dev.dwhipstock.pos.base.ConflictException
import dev.dwhipstock.pos.restaurant.CardInFlight
import dev.dwhipstock.pos.restaurant.CardPaymentGuard
import dev.dwhipstock.pos.restaurant.CheckService
import dev.dwhipstock.pos.restaurant.CheckView
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** GET /checks/{id}/card-pending: what is still on the reader for this check, after settling it. */
@Serializable
data class CardPendingView(
    val checkId: Int,
    /** Still in progress on the reader (other tenders, void and close wait). */
    val pending: List<InFlightCard>,
    /** Settled by this call: RECORDED (the guest paid), or ended with nothing taken. */
    val resolved: List<InFlightCard> = emptyList(),
    val check: CheckView,
)

/**
 * Card payments left in flight by a power cut or a crash (the store, or the
 * tablet in the middle of a card payment). The reader may have approved the
 * card while nobody was looking, so:
 *
 *  - [sweep] settles every in-flight payment with its reader / Stripe, at
 *    startup and every [start] interval: approved → recorded once (the same
 *    idempotent path as the cashier's poll), declined / cancelled / timed out
 *    → ended and the check unlocked, reader unreachable → left pending.
 *  - [requireNoCardInFlight] (the [CheckService.cardGuard]) refuses any other
 *    tender, a void or a close on a check while a card payment for it (or its
 *    bill group) is still in progress — after one synchronous settle, so a
 *    payment that resolved meanwhile doesn't block.
 *  - [managerCancel] is the override: cancel on the reader, closed only when
 *    the reader / Stripe confirms nothing was taken (else it is recorded).
 */
class PendingCardPayments(
    private val checks: CheckService,
    private val terminals: TerminalPaymentService?,
    private val stripe: StripeService?,
) : CardPaymentGuard {
    companion object {
        private val log = LoggerFactory.getLogger(PendingCardPayments::class.java)
        const val DEFAULT_SWEEP_SECONDS = 30L
        const val PENDING_CODE = "card_payment_pending"
    }

    @Volatile private var executor: ScheduledExecutorService? = null

    private fun inFlight(checkId: Int?): List<InFlightCard> =
        (terminals?.inFlight(checkId).orEmpty()) + (stripe?.inFlight(checkId).orEmpty())

    /** Settle one; returns where it is now (with readerOffline when the reader didn't answer). */
    private fun settle(card: InFlightCard): InFlightCard = when (card.source) {
        InFlightCard.TERMINAL -> terminals?.reconcile(card.paymentId)?.let { v ->
            card.copy(status = v.status, prompt = v.prompt, readerOffline = v.readerOffline)
        } ?: card
        else -> stripe?.reconcile(card.paymentId)?.let { (status, offline) -> card.copy(status = status, readerOffline = offline) } ?: card
    }

    private fun stillInFlight(card: InFlightCard) =
        card.status in (if (card.source == InFlightCard.TERMINAL) CardInFlight.TERMINAL else CardInFlight.STRIPE)

    /** Settle every in-flight card payment in the store. Returns how many ended (recorded or not). */
    fun sweep(): Int {
        val all = runCatching { inFlight(null) }.getOrElse { log.warn("card sweep: ${it.message}"); return 0 }
        var ended = 0
        for (card in all) {
            val now = runCatching { settle(card) }.getOrElse { log.warn("card sweep ${card.paymentId}: ${it.message}"); card }
            if (!stillInFlight(now)) {
                ended++
                log.info("Card payment ${card.paymentId} on check #${card.checkId} settled after the fact: ${now.status}")
            }
        }
        return ended
    }

    /** Sweep now (store startup) and then every [intervalSeconds], on a daemon thread. 0 = the startup sweep only. */
    fun start(intervalSeconds: Long = DEFAULT_SWEEP_SECONDS) {
        if (executor != null) return
        val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "card-sweep").apply { isDaemon = true } }
        executor = ex
        if (intervalSeconds > 0) {
            ex.scheduleWithFixedDelay({ runCatching { sweep() } }, 0, intervalSeconds, TimeUnit.SECONDS)
            log.info("Card payments: in-flight sweep at startup and every $intervalSeconds s")
        } else ex.execute { runCatching { sweep() } }
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    override fun requireNoCardInFlight(checkId: Int, groupId: Int?) {
        // the database first: nothing in flight (nearly always) costs one query, no reader call
        if (CardInFlight.on(checkId).none { CardInFlight.blocks(it, groupId) }) return
        val relevant = inFlight(checkId).filter { CardInFlight.blocks(it.groupId, groupId) }
        val still = relevant.map { runCatching { settle(it) }.getOrDefault(it) }.filter(::stillInFlight)
        if (still.isEmpty()) return
        throw ConflictException(
            "a card payment is still in progress on the reader for this check; wait for it, or a manager can cancel it",
            PENDING_CODE,
            details = buildJsonObject {
                put("checkId", checkId)
                put("pending", JsonArray(still.map { c ->
                    buildJsonObject {
                        put("paymentId", c.paymentId)
                        put("provider", c.provider)
                        c.groupId?.let { put("groupId", it) }
                        put("amountCents", c.amountCents)
                        put("readerOffline", JsonPrimitive(c.readerOffline))
                    }
                }))
            },
        )
    }

    /** Settle this check's in-flight card payments and say where they are. */
    fun status(checkId: Int): CardPendingView {
        val settled = inFlight(checkId).map { runCatching { settle(it) }.getOrDefault(it) }
        val (still, done) = settled.partition(::stillInFlight)
        return CardPendingView(checkId, still, done, checks.getCheck(checkId))
    }

    /** The manager's "Cancel card payment" (see [TerminalPaymentService.cancelConfirmed], [StripeService.cancelConfirmed]). */
    fun managerCancel(checkId: Int, paymentId: String, managerId: String): CardPendingView {
        val card = inFlight(checkId).firstOrNull { it.paymentId == paymentId }
            ?: return status(checkId) // already settled (recorded or ended)
        log.info("Card payment $paymentId on check #$checkId: manager $managerId cancels it")
        when (card.source) {
            InFlightCard.TERMINAL -> terminals?.cancelConfirmed(paymentId)
            else -> stripe?.cancelConfirmed(paymentId)
        }
        val view = status(checkId)
        val ended = view.resolved.firstOrNull { it.paymentId == paymentId }
            ?: InFlightCard(paymentId, card.source, card.provider, checkId, card.groupId, card.amountCents,
                status = statusOf(card), startedAt = card.startedAt)
        return view.copy(resolved = (view.resolved.filter { it.paymentId != paymentId } + ended))
    }

    private fun statusOf(card: InFlightCard): String = when (card.source) {
        InFlightCard.TERMINAL -> terminals?.reconcile(card.paymentId)?.status ?: card.status
        else -> stripe?.reconcile(card.paymentId)?.first ?: card.status
    }
}
