package dev.dwhipstock.pos

import dev.dwhipstock.pos.forecourt.ForecourtAdapter
import dev.dwhipstock.pos.forecourt.ForecourtRefused
import dev.dwhipstock.pos.forecourt.ForecourtSnapshot
import dev.dwhipstock.pos.forecourt.ForecourtUnavailable
import dev.dwhipstock.pos.forecourt.FuelGrade
import dev.dwhipstock.pos.forecourt.FuelMode
import dev.dwhipstock.pos.forecourt.FuelTrx
import dev.dwhipstock.pos.forecourt.LiveSale
import dev.dwhipstock.pos.forecourt.Pump
import dev.dwhipstock.pos.forecourt.PumpAuthorisation
import dev.dwhipstock.pos.forecourt.PumpState
import dev.dwhipstock.pos.forecourt.TrxState
import dev.dwhipstock.pos.forecourt.fuelAmountCents

/**
 * An in-memory forecourt controller for the store's tests: just enough of
 * the FDC's behaviour (authorise, a fuelling that completes, the transaction
 * buffer with lock / unlock / clear, going offline) to drive the store's
 * forecourt logic without a simulator process. The simulator's own state
 * machine is tested in forecourt/simulator.
 */
class FakeForecourt(pumps: Int = 8) : ForecourtAdapter {
    var offline = false
    val calls = mutableListOf<String>()
    private var seq = 0
    private val prices = mutableMapOf("REG" to 2899L, "MID" to 3299L, "PRE" to 3699L, "DSL" to 3499L)
    private val names = mapOf("REG" to "Regular", "MID" to "Mid-Grade", "PRE" to "Premium", "DSL" to "Diesel")

    private class P(val n: Int) {
        var state = PumpState.IDLE
        var auth: PumpAuthorisation? = null
        var live: LiveSale? = null
    }
    private val pumps = (1..pumps).associateWith { P(it) }
    val trx = linkedMapOf<String, FuelTrx>()

    override val description = "fake forecourt"

    private fun up() { if (offline) throw ForecourtUnavailable("connection refused") }
    private fun pump(n: Int) = pumps[n] ?: throw ForecourtRefused("NOT_FOUND", "no pump $n")

    override fun snapshot(): ForecourtSnapshot {
        up()
        return ForecourtSnapshot(
            pumps = pumps.values.map { p ->
                Pump(p.n, p.state, null, p.live != null, p.auth, p.live, p.live?.grade, p.live?.priceMills ?: 0,
                    p.live?.volumeMilli ?: 0, p.live?.amountCents ?: 0, null)
            },
            transactions = trx.values.filter { it.state != TrxState.CLEARED },
            grades = prices.map { (g, m) -> FuelGrade(g, names.getValue(g), names.getValue(g), m) },
        )
    }

    override fun authorise(pump: Int, mode: FuelMode, maxAmountCents: Long?, posRef: String): String {
        up(); calls += "authorise $pump $mode $maxAmountCents $posRef"
        val p = pump(pump)
        if (p.state == PumpState.EMERGENCY_STOP) throw ForecourtRefused("EMERGENCY_STOP", "pump $pump is stopped")
        if (p.state != PumpState.IDLE && p.state != PumpState.CALLING) throw ForecourtRefused("PUMP_BUSY", "pump $pump is busy")
        val id = "A-%06d".format(++seq)
        p.auth = PumpAuthorisation(id, mode, maxAmountCents, posRef)
        p.state = PumpState.AUTHORISED
        return id
    }

    override fun free(pump: Int) { up(); calls += "free $pump"; pump(pump).apply { auth = null; state = PumpState.IDLE } }
    override fun stop(pump: Int) { up(); calls += "stop $pump"; pump(pump).apply { if (state == PumpState.FUELLING) state = PumpState.SUSPENDED } }
    override fun resume(pump: Int) { up(); calls += "resume $pump"; pump(pump).apply { if (state == PumpState.SUSPENDED) state = PumpState.FUELLING } }
    override fun emergencyStop(pump: Int?) {
        up(); calls += "emergency-stop ${pump ?: "all"}"
        (if (pump == null) pumps.values else listOf(pump(pump))).forEach { p ->
            p.live?.let { completeLive(p, "EMERGENCY_STOP") }
            p.auth = null
            p.state = PumpState.EMERGENCY_STOP
        }
    }
    override fun reset(pump: Int) { up(); calls += "reset $pump"; pump(pump).state = PumpState.IDLE }

    override fun lock(trxId: String, posRef: String): FuelTrx {
        up(); calls += "lock $trxId $posRef"
        val t = trx[trxId] ?: throw ForecourtRefused("NOT_FOUND", "no $trxId")
        if (t.state == TrxState.LOCKED && t.lockedBy != posRef) throw ForecourtRefused("TRX_LOCKED", "$trxId is locked")
        return t.copy(state = TrxState.LOCKED, lockedBy = posRef).also { trx[trxId] = it }
    }
    override fun unlock(trxId: String): FuelTrx {
        up(); calls += "unlock $trxId"
        val t = trx[trxId] ?: throw ForecourtRefused("NOT_FOUND", "no $trxId")
        return t.copy(state = TrxState.PAYABLE, lockedBy = null).also { trx[trxId] = it }
    }
    override fun clear(trxId: String): FuelTrx {
        up(); calls += "clear $trxId"
        val t = trx[trxId] ?: throw ForecourtRefused("NOT_FOUND", "no $trxId")
        return t.copy(state = TrxState.CLEARED).also { trx[trxId] = it }
    }
    override fun setPrices(prices: Map<String, Long>) { up(); calls += "prices"; this.prices.putAll(prices) }

    // ---- the customer at the pump ----

    /** Lift, pump [volumeMilli] of [grade] (stopping at a prepay's limit), hang up. Returns the trx. */
    fun fillUp(pump: Int, grade: String, volumeMilli: Long, postpayAuthorised: Boolean = true): FuelTrx {
        val p = pump(pump)
        if (p.auth == null && postpayAuthorised) error("pump $pump is not authorised")
        val price = prices.getValue(grade)
        val max = p.auth?.maxAmountCents
        var vol = volumeMilli
        var amount = fuelAmountCents(vol, price)
        if (max != null && amount >= max) {
            amount = max
            vol = max * 10_000 / price
        }
        p.live = LiveSale("T-%06d".format(++seq), 1, grade, price, vol, amount, max, max != null && amount == max)
        p.state = PumpState.FUELLING
        return completeLive(p, if (p.live!!.limitReached) "LIMIT" else "HANGUP")
    }

    private fun completeLive(p: P, reason: String): FuelTrx {
        val l = p.live!!
        val a = p.auth
        val t = FuelTrx(l.trxId, p.n, l.nozzle, l.grade!!, names.getValue(l.grade), l.priceMills, l.volumeMilli,
            l.amountCents, TrxState.PAYABLE, a?.mode ?: FuelMode.POSTPAY, a?.maxAmountCents, a?.posRef, null, reason)
        trx[t.trxId] = t
        p.live = null
        p.auth = null
        p.state = PumpState.IDLE
        return t
    }
}
