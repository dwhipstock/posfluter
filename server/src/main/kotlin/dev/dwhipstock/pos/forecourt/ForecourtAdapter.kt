package dev.dwhipstock.pos.forecourt

/**
 * The store's side of the forecourt: how it talks to whatever drives the
 * pumps. Real sites have a Forecourt Device Controller (FDC) speaking the
 * IFSF / Conexxus POS-to-FDC interface; this demo has the forecourt simulator
 * (`forecourt/simulator`, [SimulatorAdapter]). A real FDC adapter implements
 * the same interface and nothing else in the store changes (docs/forecourt.md).
 *
 * Every call is synchronous and quick (sub-second timeouts): the store polls
 * [snapshot] in the background, and the counter's commands (authorise, stop)
 * fail fast when the controller can't be reached — [ForecourtUnavailable] —
 * so an unreachable forecourt never slows an in-store sale.
 *
 * Units, as on the wire: gallons in thousandths ([Pump.volumeMilli]), prices
 * per gallon in thousandths of a dollar ([FuelGrade.priceMills]), money in cents.
 */
interface ForecourtAdapter {
    /** One call: every pump and every transaction not yet cleared (FDC: GetFPState + GetFuelSaleTrxDetails). */
    fun snapshot(): ForecourtSnapshot

    /** Release a pump. [maxAmountCents] = a prepay's limit; null = postpay (AuthoriseFuelPoint). */
    fun authorise(pump: Int, mode: FuelMode, maxAmountCents: Long?, posRef: String): String

    /** Cancel an authorisation before any fuel flows (FreeFuelPoint). */
    fun free(pump: Int)

    /** Pause a pump mid-fuelling (StopFuelPoint); [resume] lets it continue (StartFuelPoint). */
    fun stop(pump: Int)
    fun resume(pump: Int)

    /** Stop one pump, or every pump when [pump] is null, at once (EmergencyStop). */
    fun emergencyStop(pump: Int?)

    /** Leave emergency stop / error (CancelEmergencyStop). */
    fun reset(pump: Int)

    /** Reserve a payable transaction for a sale (LockFuelSaleTrx), let it go (Unlock), settle it (ClearFuelSaleTrx). */
    fun lock(trxId: String, posRef: String): FuelTrx
    fun unlock(trxId: String): FuelTrx
    fun clear(trxId: String): FuelTrx

    /** Tell the controller the store's prices (ChangeFuelPrice). */
    fun setPrices(prices: Map<String, Long>)

    /** Short description for logs and /forecourt: "simulator at http://…". */
    val description: String
}

enum class FuelMode { PREPAY, POSTPAY }

/**
 * A pump (FDC "fuel point") state, as the controller reports it. IDLE ready;
 * CALLING a nozzle is up, waiting for the cashier; AUTHORISED released;
 * FUELLING product flowing (or paused on the trigger); SUSPENDED stopped by
 * the counter mid-sale; EMERGENCY_STOP / ERROR / OFFLINE out of service.
 */
enum class PumpState { IDLE, CALLING, AUTHORISED, FUELLING, SUSPENDED, EMERGENCY_STOP, ERROR, OFFLINE }

data class FuelGrade(
    val code: String, // REG | MID | PRE | DSL
    val name: String,
    val nameEs: String,
    val priceMills: Long,
)

data class PumpAuthorisation(val authId: String, val mode: FuelMode, val maxAmountCents: Long?, val posRef: String?)

/** A sale in progress on a pump: what the dispenser face is counting. */
data class LiveSale(
    val trxId: String,
    val nozzle: Int?,
    val grade: String?,
    val priceMills: Long,
    val volumeMilli: Long,
    val amountCents: Long,
    val maxAmountCents: Long?,
    val limitReached: Boolean,
)

data class Pump(
    val pump: Int,
    val state: PumpState,
    val nozzleUp: Int?,
    val flowing: Boolean,
    val authorisation: PumpAuthorisation?,
    val current: LiveSale?,
    /** What the dispenser shows: the live sale, or the last one until the next lift. */
    val displayGrade: String?,
    val displayPriceMills: Long,
    val displayVolumeMilli: Long,
    val displayAmountCents: Long,
    val error: String?,
)

enum class TrxState { PAYABLE, LOCKED, CLEARED }

/** A completed fuelling in the controller's buffer (FDC "fuel sale transaction"). */
data class FuelTrx(
    val trxId: String,
    val pump: Int,
    val nozzle: Int?,
    val grade: String,
    val gradeName: String,
    val priceMills: Long,
    val volumeMilli: Long,
    val amountCents: Long,
    val state: TrxState,
    val mode: FuelMode,
    val maxAmountCents: Long?,
    val posRef: String?,
    val lockedBy: String?,
    val reason: String?,
)

data class ForecourtSnapshot(
    val pumps: List<Pump>,
    val transactions: List<FuelTrx>,
    val grades: List<FuelGrade>,
)

/** The controller could not be reached (or answered garbage): pumps show offline, the shop keeps selling. */
class ForecourtUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The controller refused a command: [code] is its reason (PUMP_BUSY, EMERGENCY_STOP, TRX_LOCKED…). */
class ForecourtRefused(val code: String, message: String) : RuntimeException(message)

/**
 * Amount for [volumeMilli] thousandths of a gallon at [priceMills] thousandths
 * of a dollar a gallon, in cents, half-up: 10.052 gal × $3.299 = $33.16.
 */
fun fuelAmountCents(volumeMilli: Long, priceMills: Long): Long =
    java.math.BigInteger.valueOf(volumeMilli).multiply(java.math.BigInteger.valueOf(priceMills))
        .add(java.math.BigInteger.valueOf(5_000)).divide(java.math.BigInteger.valueOf(10_000)).toLong()

/** "10.052" */
fun gallonsText(volumeMilli: Long): String = "%d.%03d".format(volumeMilli / 1000, volumeMilli % 1000)

/** "3.299" */
fun pricePerGallonText(priceMills: Long): String = "%d.%03d".format(priceMills / 1000, priceMills % 1000)
