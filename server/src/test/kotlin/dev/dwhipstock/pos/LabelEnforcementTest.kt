package dev.dwhipstock.pos

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The server owns table labels: create/rename always coerce to the zone's
 * "{prefix}-{n}" form, honouring a free requested number but fixing the prefix,
 * so a hand-typed mismatch (a B1 in Lower) is impossible and numbers stay unique
 * per zone. Seeded zones: upper=U (1..17 taken), outside=O (1..4 taken).
 */
class LabelEnforcementTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun HttpClient.addLabel(zone: String, body: String): String {
        val res = post("/zones/$zone/tables") {
            contentType(ContentType.Application.Json); setBody(body)
        }
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        return json.parseToJsonElement(res.bodyAsText()).jsonObject["label"]!!.jsonPrimitive.content
    }

    @Test
    fun `wrong-prefix create is coerced to the zone prefix, taken number bumped`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        // "B1" in Upper: prefix fixed to U; 1 is taken (U-1) so it lands on the next free
        val label = c.addLabel("upper", """{"label":"B1","x":100,"y":100,"managerPin":"1234"}""")
        assertEquals("U-18", label)
    }

    @Test
    fun `free requested number is honoured, only the prefix corrected`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        // Outside has O-1 through O-4 — 7 is free, so "B7" becomes O-7 (kept number, fixed prefix)
        assertEquals("O-7", c.addLabel("outside", """{"label":"B7","x":100,"y":100,"managerPin":"1234"}"""))
    }

    @Test
    fun `no label auto-assigns the next free number`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        assertEquals("O-5", c.addLabel("outside", """{"x":100,"y":100,"managerPin":"1234"}"""))
        // and the next one is sequential
        assertEquals("O-6", c.addLabel("outside", """{"x":140,"y":140,"managerPin":"1234"}"""))
    }

    @Test
    fun `rename also coerces to the zone prefix`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        // rename Upper's t1 (U-1) with a wrong-prefix, free number → U-99
        val res = c.patch("/tables/t1") {
            contentType(ContentType.Application.Json)
            setBody("""{"label":"X99","managerPin":"1234"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals("U-99", json.parseToJsonElement(res.bodyAsText()).jsonObject["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun `two adds requesting the same number do not collide`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        // outside 5 is free first time...
        assertEquals("O-5", c.addLabel("outside", """{"label":"5","x":100,"y":100,"managerPin":"1234"}"""))
        // ...second request for 5 is bumped to the next free number
        assertEquals("O-6", c.addLabel("outside", """{"label":"5","x":150,"y":150,"managerPin":"1234"}"""))
    }
}
