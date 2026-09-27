package dev.dwhipstock.pos.payments

import dev.dwhipstock.pos.payments.simulator.SimulatedTerminalDevice
import dev.dwhipstock.pos.payments.simulator.SimulatedTerminalDevice.Scenario
import dev.dwhipstock.pos.payments.simulator.SimulatedTerminalDevice.Settings
import dev.dwhipstock.pos.payments.simulator.SimulatedTerminalDevice.TestCard
import dev.dwhipstock.pos.payments.terminal.EntryMode
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SimOperatorPanelTest {
    private val clock = AtomicLong(1_000_000L)
    private val device = SimulatedTerminalDevice(clock = clock::get,
        delays = SimulatedTerminalDevice.Delays(tapMs = 1_000, swipeMs = 1_000, chipMs = 1_000, jitterMs = 0, resultScreenMs = 0),
        random = kotlin.random.Random(3))

    private fun pay(scenario: Scenario = Scenario.APPROVE, tip: Boolean = false): String {
        val t = device.start("ref-${clock.get()}", 2_000, "USD", tipOnReader = tip, timeoutSeconds = 60)
        if (device.get(t.id).state == "TIP") device.chooseTip(0)
        device.present(EntryMode.TAP, TestCard.VISA, scenario)
        return t.id
    }

    @Test
    fun `response delay adds to processing`() {
        device.updateSettings(Settings(responseDelayMs = 5_000))
        val id = pay()
        clock.addAndGet(2_000)
        assertEquals("PROCESSING", device.get(id).state)
        clock.addAndGet(4_500)
        assertEquals("APPROVED", device.get(id).state)
    }

    @Test
    fun `forced errors decline without reaching the processor`() {
        device.updateSettings(Settings(forceError = "total_error"))
        val a = pay(); clock.addAndGet(1_500)
        assertEquals("terminal_error", device.get(a).declineCode)
        device.updateSettings(Settings(forceError = "processor_unreachable"))
        val b = pay(); clock.addAndGet(1_500)
        assertEquals("processor_unavailable", device.get(b).declineCode)
    }

    @Test
    fun `expired and lost cards decline`() {
        val a = pay(Scenario.EXPIRED_CARD); clock.addAndGet(1_500)
        assertEquals("expired_card", device.get(a).declineCode)
        val b = pay(Scenario.LOST_CARD); clock.addAndGet(1_500)
        assertEquals("lost_card", device.get(b).declineCode)
    }

    @Test
    fun `auto tip answers the tip prompt`() {
        device.updateSettings(Settings(autoTipPercent = 15))
        val t = device.start("tip-1", 2_000, "USD", tipOnReader = true, timeoutSeconds = 60)
        val v = device.get(t.id)
        assertEquals("PRESENT_CARD", v.state)
        assertEquals(300, v.tipCents)
    }

    @Test
    fun `transaction monitor logs the payment and bad settings are refused`() {
        val id = pay(); clock.addAndGet(1_500); device.get(id)
        val text = device.logSince(0).joinToString("\n") { it.text }
        assertTrue("START $id" in text && "CARD Visa" in text && "RESULT $id APPROVED" in text, text)
        val last = device.logSince(0).last().seq
        assertEquals(emptyList(), device.logSince(last))
        assertFailsWith<IllegalArgumentException> { device.updateSettings(Settings(forceError = "boom")) }
    }
}
