package dev.dwhipstock.pos

import com.sun.net.httpserver.HttpServer
import dev.dwhipstock.pos.forecourt.ForecourtRefused
import dev.dwhipstock.pos.forecourt.ForecourtUnavailable
import dev.dwhipstock.pos.forecourt.FuelMode
import dev.dwhipstock.pos.forecourt.PumpState
import dev.dwhipstock.pos.forecourt.SimulatorAdapter
import dev.dwhipstock.pos.forecourt.TrxState
import dev.dwhipstock.pos.forecourt.fuelAmountCents
import dev.dwhipstock.pos.forecourt.gallonsText
import dev.dwhipstock.pos.forecourt.pricePerGallonText
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The simulator adapter speaks the simulator's FDC-like JSON (docs/forecourt.md)
 * and turns "no answer" into [ForecourtUnavailable] and a refusal into
 * [ForecourtRefused] — here against a canned local HTTP server.
 */
class SimulatorAdapterTest {
    private val requests = mutableListOf<String>()
    private var server: HttpServer? = null

    @AfterTest fun stop() { server?.stop(0) }

    private fun serve(routes: Map<String, Pair<Int, String>>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val key = "${ex.requestMethod} ${ex.requestURI.path}"
            requests += key + " " + ex.requestBody.readBytes().decodeToString()
            val (code, body) = routes[key] ?: (404 to """{"result":"Failure","error":{"code":"NOT_FOUND","message":"no route"}}""")
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private val status = """
        {"result":"Success","fdcMessage":"GetFDCStatus","fdcId":"SIM-FDC-1",
         "grades":[{"grade":"REG","name":"Regular","nameEs":"Regular","productNo":1,"priceMills":2899}],
         "pumps":[
          {"pump":2,"state":"IDLE","fdcState":"FDC_READY","nozzleUp":null,"flowing":false,"authorisation":null,"current":null,
           "display":{"grade":null,"priceMills":0,"volumeMilli":0,"amountCents":0},"error":null},
          {"pump":1,"state":"FUELLING","nozzleUp":2,"flowing":true,
           "authorisation":{"authId":"A-000001","mode":"PREPAY","maxAmountCents":4000,"posRef":"prepay-7"},
           "current":{"trxId":"T-000003","nozzle":2,"grade":"MID","gradeName":"Mid-Grade","priceMills":3299,"volumeMilli":5021,"amountCents":1656,"maxAmountCents":4000,"limitReached":false},
           "display":{"grade":"MID","priceMills":3299,"volumeMilli":5021,"amountCents":1656},"error":null}],
         "transactions":[{"trxId":"T-000002","pump":2,"nozzle":1,"grade":"REG","gradeName":"Regular","priceMills":2899,
           "volumeMilli":10000,"amountCents":2899,"state":"PAYABLE","mode":"POSTPAY","maxAmountCents":null,"authId":"A-0","posRef":null,
           "lockedBy":null,"reason":"HANGUP"}]}
    """.trimIndent()

    @Test
    fun readsTheSnapshot() {
        val a = SimulatorAdapter(serve(mapOf("GET /fdc/v1/status" to (200 to status))))
        val snap = a.snapshot()
        assertEquals(listOf(1, 2), snap.pumps.map { it.pump })
        val p1 = snap.pumps[0]
        assertEquals(PumpState.FUELLING, p1.state)
        assertEquals(FuelMode.PREPAY, p1.authorisation!!.mode)
        assertEquals(4000, p1.authorisation!!.maxAmountCents)
        assertEquals("prepay-7", p1.authorisation!!.posRef)
        assertEquals(5021, p1.current!!.volumeMilli)
        assertEquals(PumpState.IDLE, snap.pumps[1].state)
        assertNull(snap.pumps[1].current)
        val t = snap.transactions.single()
        assertEquals(TrxState.PAYABLE, t.state)
        assertEquals(FuelMode.POSTPAY, t.mode)
        assertEquals(2899, t.amountCents)
        assertEquals("Regular", snap.grades.single().name)
    }

    @Test
    fun sendsCommandsAsFdcMessages() {
        val trx = """{"trxId":"T-1","pump":2,"grade":"REG","gradeName":"Regular","priceMills":2899,"volumeMilli":1000,"amountCents":290,"state":"LOCKED","mode":"POSTPAY","lockedBy":"sale-4"}"""
        val a = SimulatorAdapter(serve(mapOf(
            "POST /fdc/v1/pumps/3/authorise" to (200 to """{"result":"Success","fdcMessage":"AuthoriseFuelPoint","authId":"A-000009"}"""),
            "POST /fdc/v1/transactions/T-1/lock" to (200 to """{"result":"Success","transaction":$trx}"""),
            "POST /fdc/v1/emergency-stop" to (200 to """{"result":"Success"}"""),
        )))
        assertEquals("A-000009", a.authorise(3, FuelMode.PREPAY, 4000, "prepay-9"))
        assertTrue(requests.any { it.startsWith("POST /fdc/v1/pumps/3/authorise") && it.contains("\"maxAmountCents\":4000") && it.contains("prepay-9") })
        assertEquals("sale-4", a.lock("T-1", "sale-4").lockedBy)
        a.emergencyStop(null)
        assertTrue(requests.any { it.startsWith("POST /fdc/v1/emergency-stop") })
    }

    @Test
    fun aRefusalCarriesTheControllersReason() {
        val a = SimulatorAdapter(serve(mapOf(
            "POST /fdc/v1/pumps/1/authorise" to (409 to """{"result":"Failure","error":{"code":"PUMP_BUSY","message":"pump 1 is fuelling"}}"""),
        )))
        val e = assertFailsWith<ForecourtRefused> { a.authorise(1, FuelMode.POSTPAY, null, "postpay") }
        assertEquals("PUMP_BUSY", e.code)
    }

    @Test
    fun noControllerIsUnavailableNotACrash() {
        val port = ServerSocket(0).use { it.localPort } // free, and nobody listening
        val a = SimulatorAdapter("http://127.0.0.1:$port")
        assertFailsWith<ForecourtUnavailable> { a.snapshot() }
        assertFailsWith<ForecourtUnavailable> { a.authorise(1, FuelMode.POSTPAY, null, "postpay") }
        // a server error is "unavailable" too
        val b = SimulatorAdapter(serve(mapOf("GET /fdc/v1/status" to (500 to "oops"))))
        assertFailsWith<ForecourtUnavailable> { b.snapshot() }
    }

    @Test
    fun fuelMathIsHalfUpToTheCent() {
        assertEquals(3316, fuelAmountCents(10_052, 3_299)) // 33.161…
        assertEquals(2899, fuelAmountCents(10_000, 2_899))
        assertEquals(1, fuelAmountCents(2, 2_899)) // 0.0058 → 0.01
        assertEquals(0, fuelAmountCents(0, 2_899))
        assertEquals("10.052", gallonsText(10_052))
        assertEquals("0.007", gallonsText(7))
        assertEquals("3.299", pricePerGallonText(3_299))
    }
}
