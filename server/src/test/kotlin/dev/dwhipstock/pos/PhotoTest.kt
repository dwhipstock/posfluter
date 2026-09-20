package dev.dwhipstock.pos

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotoTest {

    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).toString()

    // 1×1 transparent PNG
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

    private fun multipart(pin: String?, bytes: ByteArray, contentType: String) =
        MultiPartFormDataContent(formData {
            pin?.let { append("managerPin", it) }
            append("photo", bytes, Headers.build {
                append(HttpHeaders.ContentType, contentType)
                append(HttpHeaders.ContentDisposition, "filename=\"photo.png\"")
            })
        })

    @Test
    fun uploadServeAndGate() = testApplication {
        application {
            module(dbPath = tempDir("pos-photo") + "/pos.db", photosDir = tempDir("photos"))
        }
        val manager = loginClient()

        // no photo yet → 404, and /items has photoVersion null
        assertEquals(HttpStatusCode.NotFound, client.get("/photos/lantern-lager").status)

        // upload without manager PIN → 403
        assertEquals(HttpStatusCode.Forbidden, manager.post("/items/lantern-lager/photo") {
            setBody(multipart(null, png, "image/png"))
        }.status)

        // wrong type rejected
        assertEquals(HttpStatusCode.BadRequest, manager.post("/items/lantern-lager/photo") {
            setBody(multipart("1234", png, "image/gif"))
        }.status)

        // over the 2MB cap rejected
        assertEquals(HttpStatusCode.BadRequest, manager.post("/items/lantern-lager/photo") {
            setBody(multipart("1234", ByteArray(2 * 1024 * 1024 + 1), "image/png"))
        }.status)

        // happy path: manager-approved PNG upload
        assertEquals(HttpStatusCode.Created, manager.post("/items/lantern-lager/photo") {
            setBody(multipart("1234", png, "image/png"))
        }.status)

        // unknown item 404s
        assertEquals(HttpStatusCode.NotFound, manager.post("/items/nope/photo") {
            setBody(multipart("1234", png, "image/png"))
        }.status)

        // served openly (customer phones) with cache headers
        val served = client.get("/photos/lantern-lager")
        assertEquals(HttpStatusCode.OK, served.status)
        assertEquals("image/png", served.headers[HttpHeaders.ContentType])
        assertTrue(served.headers[HttpHeaders.ETag] != null)
        assertTrue(served.readRawBytes().contentEquals(png))

        // conditional GET → 304
        val etag = served.headers[HttpHeaders.ETag]!!
        assertEquals(HttpStatusCode.NotModified, client.get("/photos/lantern-lager") {
            header(HttpHeaders.IfNoneMatch, etag)
        }.status)

        // /items now reports a photoVersion for lantern-lager (the only item with a photo)
        val items = manager.get("/items").bodyAsText()
        assertTrue(Regex("\"id\":\"lantern-lager\".*?\"photoVersion\":\\d+").containsMatchIn(items),
            "expected photoVersion on lantern-lager in $items")
    }
}
