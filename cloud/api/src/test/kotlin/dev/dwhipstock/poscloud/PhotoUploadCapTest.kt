package dev.dwhipstock.poscloud

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The photo sideband must enforce the 2MB cap WHILE streaming, so an oversized
 * body is rejected (413) the moment it passes the cap rather than being buffered
 * whole onto the API heap first. Regression for the buffer-then-check OOM.
 */
class PhotoUploadCapTest {

    private val key = "store-key-photo"

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedStoreKey("copperlantern", "vieux-port", key)
    }

    @Test
    fun oversizedUploadIsRejectedWith413() = testApplication {
        application { module(TestSupport.config) }
        val tooBig = ByteArray(3 * 1024 * 1024) // 3MB > 2MB cap
        val res = client.post("/v1/ingest/photos/lantern-lager") {
            header(HttpHeaders.Authorization, "Bearer $key")
            setBody(MultiPartFormDataContent(formData {
                append("photo", tooBig, Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"big.jpg\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, res.status)
        assertEquals("photo_too_large",
            testJson.parseToJsonElement(res.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun withinCapUploadStillSucceeds() = testApplication {
        application { module(TestSupport.config) }
        val ok = ByteArray(512 * 1024) { it.toByte() } // 512KB, under the cap
        val res = client.post("/v1/ingest/photos/lantern-lager") {
            header(HttpHeaders.Authorization, "Bearer $key")
            setBody(MultiPartFormDataContent(formData {
                append("photo", ok, Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"ok.jpg\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }
}
