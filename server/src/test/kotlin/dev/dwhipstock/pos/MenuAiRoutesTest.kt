package dev.dwhipstock.pos

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.dwhipstock.pos.aimenu.MenuAiProvider
import dev.dwhipstock.pos.aimenu.MenuImage
import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Translations
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.sdk.MenuAiConfig
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.Base64
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A menu model that answers a canned reply and remembers what it was sent. */
class FakeMenuProvider(var reply: String = """{"summary":"","ops":[]}""") : MenuAiProvider {
    override val id = "fake"
    override val model = "fake-menu-1"
    override val host = "menu.example.test"
    val prompts = mutableListOf<String>()
    val images = mutableListOf<MenuImage>()
    override fun complete(system: String, user: String, images: List<MenuImage>): String {
        prompts += system + "\n" + user
        this.images += images
        return reply
    }
}

class MenuAiRoutesTest {
    private val key = "sk-ant-SECRET-never-leaves-0123456789"
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).toString()

    private fun on() = MenuAiConfig.fromProperties(Properties().apply {
        setProperty("menu.ai", "on")
        setProperty("menu.ai.provider", "anthropic")
        setProperty("menu.ai.anthropic.apiKey", key)
    })

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private fun ApplicationTestBuilder.store(fake: FakeMenuProvider, online: Boolean = true) = application {
        module(dbPath = tempDir("pos-menu-ai") + "/pos.db", photosDir = tempDir("photos"),
            menuAiConfig = on(), menuAiProvider = fake, imageReachable = { online })
    }

    private fun outbox(): List<String> = transaction { SyncOutbox.selectAll().map { it[SyncOutbox.eventType] } }
    private fun price(variantId: String) = transaction {
        ItemVariants.selectAll().where { ItemVariants.id eq variantId }.first()[ItemVariants.priceCents]
    }
    private fun item(id: String) = transaction { Items.selectAll().where { Items.id eq id }.firstOrNull() }

    @Test
    fun offByDefault() = testApplication {
        application { module(dbPath = tempDir("pos-menu-ai") + "/pos.db", photosDir = tempDir("photos"),
            menuAiConfig = MenuAiConfig.OFF) }
        val manager = loginClient()
        val status = obj(manager.get("/menu-ai/status").bodyAsText())
        assertEquals("false", status.s("configured"))
        assertEquals("menu_ai_off", status.s("reason"))
        val res = manager.post("/menu-ai/chat") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234","text":"86 the salmon"}""")
        }
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("menu_ai_disabled", obj(res.bodyAsText()).s("code"))
    }

    @Test
    fun offlineShowsUnavailable() = testApplication {
        store(FakeMenuProvider(), online = false)
        val status = obj(loginClient().get("/menu-ai/status").bodyAsText())
        assertEquals("true", status.s("configured"))
        assertEquals("false", status.s("available"))
        assertEquals("menu_ai_offline", status.s("reason"))
    }

    @Test
    fun chatPreviewsThenAppliesTickedChangesThroughTheCatalogAndUndoes() = testApplication {
        val fake = FakeMenuProvider("""
            {"summary":"Raised burgers, 86'd the salmon, added a Caesar salad.","ops":[
              {"op":"update_item","item":"lantern-burger","priceMinor":2025},
              {"op":"update_item","item":"salmon","active":false},
              {"op":"add_category","ref":"new:salads","nameEn":"Salads","nameFr":""},
              {"op":"add_item","category":"new:salads","nameEn":"Caesar Salad","nameFr":"",
               "descriptionEn":"Romaine, parmesan, croutons.","variants":[{"labelEn":"Regular","labelFr":"","priceMinor":1400}]},
              {"op":"remove_item","item":"no-such-item"},
              {"op":"update_item","item":"poutine","priceMinor":"13.50"},
              {"op":"drop_tables"}
            ]}
        """.trimIndent())
        store(fake)
        val manager = loginClient()
        val before = price("lantern-burger:regular")

        // a manager's own session is the approval; anyone else is refused
        assertEquals(HttpStatusCode.Forbidden, loginClient("9999").post("/menu-ai/chat") {
            contentType(ContentType.Application.Json); setBody("""{"text":"raise burgers"}""")
        }.status)
        val outboxBefore = outbox().size
        val res = manager.post("/menu-ai/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"managerPin":"1234","text":"raise the copper burger by 1, 86 the salmon, add caesar salad 14 under salads"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val proposal = obj(res.bodyAsText())
        val changes = proposal["changes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("update_item", "update_item", "add_category", "add_item"), changes.map { it.s("kind") })
        // unknown ids, string prices and unknown ops are rejected, never applied
        assertEquals(3, proposal["rejected"]!!.jsonArray.size)
        val priceDetail = changes[0]["details"]!!.jsonArray.single().jsonObject
        assertEquals("price", priceDetail.s("field"))
        assertTrue(priceDetail.s("after").contains("20"))
        assertEquals(before, price("lantern-burger:regular"))
        assertEquals(outboxBefore, outbox().size)
        // the model saw the store's currency, the menu and its ids
        assertTrue(fake.prompts.single().contains("CAD"))
        assertTrue(fake.prompts.single().contains("\"lantern-burger:regular\""))

        // tick the price, the salmon and the salad (its new category comes along)
        val ids = changes.map { it.s("id") }
        val apply = manager.post("/menu-ai/apply") {
            contentType(ContentType.Application.Json)
            setBody("""{"managerPin":"1234","proposalId":"${proposal.s("proposalId")}","changeIds":["${ids[0]}","${ids[1]}","${ids[3]}"]}""")
        }
        assertEquals(HttpStatusCode.OK, apply.status, apply.bodyAsText())
        val result = obj(apply.bodyAsText())
        assertEquals("4", result.s("applied"))
        val newItem = result["createdItemIds"]!!.jsonArray.single().jsonPrimitive.content
        assertEquals(2025L, price("lantern-burger:regular"))
        assertEquals(false, item("salmon")!![Items.active])
        val salad = item(newItem)!!
        assertEquals("Caesar Salad", salad[Items.nameEn])
        assertEquals("Caesar Salad", salad[Items.nameFr]) // the second name falls back to the first
        assertEquals("CS", salad[Items.abbrev])
        // the same outbox events as a hand edit → portal sync unchanged
        val events = outbox().drop(outboxBefore)
        assertEquals(listOf("category.created", "item.variant_updated", "item.updated", "item.created"), events)

        // a proposal applies once
        assertEquals(HttpStatusCode.NotFound, manager.post("/menu-ai/apply") {
            contentType(ContentType.Application.Json)
            setBody("""{"managerPin":"1234","proposalId":"${proposal.s("proposalId")}","changeIds":["${ids[0]}"]}""")
        }.status)

        // the history has it: who, from chat, what it touched
        val setId = result.s("changeSetId")
        val entry = Json.parseToJsonElement(manager.get("/menu-ai/history").bodyAsText()).jsonArray.single().jsonObject
        assertEquals(setId, entry.s("id"))
        assertEquals("chat", entry.s("source"))
        assertEquals("false", entry.s("reverted"))

        // revert: manager only, then everything is back, through the same menu code (outbox events)
        assertEquals(HttpStatusCode.Forbidden, loginClient("9999").post("/menu-ai/history/$setId/revert") {
            contentType(ContentType.Application.Json); setBody("""{}""")
        }.status)
        val outboxBeforeRevert = outbox().size
        val revert = manager.post("/menu-ai/history/$setId/revert") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234"}""")
        }
        assertEquals(HttpStatusCode.OK, revert.status, revert.bodyAsText())
        assertEquals(before, price("lantern-burger:regular"))
        assertEquals(true, item("salmon")!![Items.active])
        assertTrue(item(newItem)!![Items.deletedAt] != null) // soft deleted, the row stays
        assertNull(transaction { Categories.selectAll().where { Categories.nameEn eq "Salads" }.firstOrNull() })
        assertEquals(listOf("item.deleted", "item.updated", "item.variant_updated", "category.deleted"),
            outbox().drop(outboxBeforeRevert))
        assertEquals("true", Json.parseToJsonElement(manager.get("/menu-ai/history").bodyAsText())
            .jsonArray.single().jsonObject.s("reverted"))
        // once only
        assertEquals(HttpStatusCode.Conflict, manager.post("/menu-ai/history/$setId/revert") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234"}""")
        }.status)
    }

    @Test
    fun `a rename in one language never bleeds into the other`() = testApplication {
        // "rename Giant Pub Pretzel to Big Pretzel": the model (wrongly) proposes the same
        // new text for nameFr too, even though the pretzel's French name ("Bretzel géant")
        // has always differed from its English one — the guard must drop the French copy.
        val fake = FakeMenuProvider("""
            {"summary":"Renamed the pretzel.","ops":[
              {"op":"update_item","item":"pretzel","nameEn":"Big Pretzel","nameFr":"Big Pretzel"}
            ]}
        """.trimIndent())
        store(fake)
        val manager = loginClient() // Demo Manager, languageCode "en" — the request language

        val res = manager.post("/menu-ai/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"rename Giant Pub Pretzel to Big Pretzel"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val proposal = obj(res.bodyAsText())
        val change = proposal["changes"]!!.jsonArray.single().jsonObject
        val details = change["details"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("nameEn"), details.map { it.s("field") })
        assertEquals("Big Pretzel", details.single().s("after"))

        val applied = manager.post("/menu-ai/apply") {
            contentType(ContentType.Application.Json)
            setBody("""{"proposalId":"${proposal.s("proposalId")}","changeIds":["${change.s("id")}"]}""")
        }
        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        val row = item("pretzel")!!
        assertEquals("Big Pretzel", row[Items.nameEn])
        assertEquals("Bretzel géant", row[Items.nameFr]) // French left alone
    }

    @Test
    fun `renaming in both languages at once still works when the two names really differ`() = testApplication {
        val fake = FakeMenuProvider("""
            {"summary":"Renamed the pretzel in both languages.","ops":[
              {"op":"update_item","item":"pretzel","nameEn":"Big Pretzel","nameFr":"Petit Bretzel"}
            ]}
        """.trimIndent())
        store(fake)
        val manager = loginClient()
        val res = manager.post("/menu-ai/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"rename the pretzel to Big Pretzel in English and Petit Bretzel in French"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val proposal = obj(res.bodyAsText())
        val change = proposal["changes"]!!.jsonArray.single().jsonObject
        val details = change["details"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("nameEn", "nameFr"), details.map { it.s("field") }.toSet())
    }

    @Test
    fun `the language-bleed guard also protects an existing es de translation`() = testApplication {
        val fake = FakeMenuProvider("""
            {"summary":"Renamed the pretzel.","ops":[
              {"op":"update_item","item":"pretzel","nameEn":"Big Pretzel","nameFr":"Big Pretzel"},
              {"op":"set_name","entity":"item","id":"pretzel","lang":"es","name":"Big Pretzel"}
            ]}
        """.trimIndent())
        store(fake)
        val manager = loginClient()
        transaction { Translations.set(Translations.ITEM, "pretzel", "es", "Pretzel gigante") }

        val res = manager.post("/menu-ai/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"rename Giant Pub Pretzel to Big Pretzel"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val proposal = obj(res.bodyAsText())
        val changes = proposal["changes"]!!.jsonArray.map { it.jsonObject }
        // the set_name copy is dropped as not-a-translation; only the English rename remains
        assertEquals(listOf("update_item"), changes.map { it.s("kind") })
        assertEquals(1, proposal["rejected"]!!.jsonArray.size)
        assertTrue(proposal["rejected"]!!.jsonArray.single().jsonPrimitive.content.contains("copy"))
    }

    @Test
    fun revertUndeletesASoftDeletedItemAndWarnsAboutLaterEdits() = testApplication {
        val fake = FakeMenuProvider("""{"summary":"Removed the salmon, poutine 14.","ops":[
            {"op":"remove_item","item":"salmon"},
            {"op":"update_item","item":"poutine","priceMinor":1400}]}""")
        store(fake)
        val manager = loginClient()
        val proposal = obj(manager.post("/menu-ai/chat") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234","text":"remove the salmon"}""")
        }.bodyAsText())
        assertEquals("remove_item", proposal["changes"]!!.jsonArray[0].jsonObject.s("kind"))
        val ids = proposal["changes"]!!.jsonArray.map { it.jsonObject.s("id") }
        val setId = obj(manager.post("/menu-ai/apply") {
            contentType(ContentType.Application.Json)
            setBody("""{"managerPin":"1234","proposalId":"${proposal.s("proposalId")}","changeIds":["${ids[0]}","${ids[1]}"]}""")
        }.bodyAsText()).s("changeSetId")
        // soft delete: hidden from the menu, the row (and old sales) stay
        assertTrue(item("salmon")!![Items.deletedAt] != null)
        assertFalse(manager.get("/items").bodyAsText().contains("\"salmon\""))

        // a later hand edit touches the poutine → revert warns first
        assertEquals(HttpStatusCode.OK, manager.patch("/items/poutine/variants/poutine:regular") {
            contentType(ContentType.Application.Json); setBody("""{"priceCents":1500}""")
        }.status)
        val warn = manager.post("/menu-ai/history/$setId/revert") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234"}""")
        }
        assertEquals(HttpStatusCode.Conflict, warn.status)
        val body = obj(warn.bodyAsText())
        assertEquals("menu_ai_revert_conflict", body.s("code"))
        assertEquals(listOf("Classic Poutine"), body["titles"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(item("salmon")!![Items.deletedAt] != null) // nothing changed yet
        assertEquals(1500L, price("poutine:regular"))

        // the manager confirms: the salmon is back (un-deleted) and the poutine price restored
        val forced = manager.post("/menu-ai/history/$setId/revert") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234","force":true}""")
        }
        assertEquals(HttpStatusCode.OK, forced.status, forced.bodyAsText())
        assertNull(item("salmon")!![Items.deletedAt])
        assertEquals(true, item("salmon")!![Items.active])
        assertEquals(1300L, price("poutine:regular"))
        assertTrue(manager.get("/items").bodyAsText().contains("\"salmon\""))
    }

    @Test
    fun aFailingChangeRollsBackTheWholeApply() = testApplication {
        val fake = FakeMenuProvider("""{"ops":[
            {"op":"update_item","item":"poutine","priceMinor":1400},
            {"op":"update_item","item":"salmon","priceMinor":2500}]}""")
        store(fake)
        val manager = loginClient()
        val proposal = obj(manager.post("/menu-ai/chat") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234","text":"prices"}""")
        }.bodyAsText())
        // the salmon is deleted by hand before the manager applies
        assertEquals(HttpStatusCode.OK, manager.delete("/items/salmon").status)
        val ids = proposal["changes"]!!.jsonArray.map { it.jsonObject.s("id") }
        val res = manager.post("/menu-ai/apply") {
            contentType(ContentType.Application.Json)
            setBody("""{"managerPin":"1234","proposalId":"${proposal.s("proposalId")}","changeIds":["${ids[0]}","${ids[1]}"]}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertEquals(1300L, price("poutine:regular"))
    }

    @Test
    fun badReplyIsTheFixedReplyAndPhotosGoToTheModel() = testApplication {
        val fake = FakeMenuProvider("Sure! Here is your menu: ...")
        store(fake)
        val manager = loginClient()
        val res = manager.post("/menu-ai/photos") {
            setBody(MultiPartFormDataContent(formData {
                append("managerPin", "1234")
                append("photo", png, Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=\"menu.png\"")
                })
            }))
        }
        // prose instead of a change set: a retryable "incomplete" reply, never the model's text
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("menu_ai_incomplete", obj(res.bodyAsText()).s("refusal"))
        assertFalse(res.bodyAsText().contains("Sure!"))
        assertEquals(1, fake.images.size)
        assertTrue(fake.prompts.single().contains("paper menu"))
    }

    @Test
    fun translateFillsMissingNamesAsAChangeSetAndReverts() = testApplication {
        val fake = FakeMenuProvider("""{"summary":"German and Spanish names.","ops":[
          {"op":"set_name","entity":"item","id":"poutine","lang":"de","name":"Poutine (Pommes mit Käse und Bratensoße)"},
          {"op":"set_name","entity":"category","id":"starters","lang":"es","name":"Entradas"},
          {"op":"set_name","entity":"item","id":"poutine","lang":"it","name":"Poutine"},
          {"op":"set_name","entity":"item","id":"no-such-item","lang":"de","name":"X"}
        ]}""")
        store(fake)
        val manager = loginClient()
        // the demo seed already has every es / de name: nothing to ask the model
        val none = obj(manager.post("/menu-ai/translate") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234"}""")
        }.bodyAsText())
        assertEquals(0, none["changes"]!!.jsonArray.size)
        assertTrue(fake.prompts.isEmpty())

        transaction {
            Translations.set(Translations.ITEM, "poutine", "de", null)
            Translations.set(Translations.ITEM, "poutine", "af", null)
            Translations.set(Translations.CATEGORY, "starters", "es", null)
        }
        assertEquals(HttpStatusCode.Forbidden, loginClient("9999").post("/menu-ai/translate") {
            contentType(ContentType.Application.Json); setBody("""{}""")
        }.status)
        // the signed-in manager needs no second PIN
        val res = manager.post("/menu-ai/translate") {
            contentType(ContentType.Application.Json); setBody("""{}""")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val proposal = obj(res.bodyAsText())
        // only the missing ones were sent, with the languages they lack
        val prompt = fake.prompts.single()
        assertTrue(prompt.contains("item poutine") && prompt.contains("→ de, af"))
        // the model is told which language each code is (af alone is easy to misread)
        assertTrue(prompt.contains("af (Afrikaans (South African))"), prompt)
        assertTrue(prompt.contains("category starters") && prompt.contains("→ es"))
        assertFalse(prompt.contains("item wings"))
        val changes = proposal["changes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("set_name", "set_name"), changes.map { it.s("kind") })
        assertEquals(2, proposal["rejected"]!!.jsonArray.size) // a language the store lacks, an unknown item
        assertNull(transaction { Translations.get(Translations.ITEM, "poutine", "de") }) // previewing changes nothing

        val apply = obj(manager.post("/menu-ai/apply") {
            contentType(ContentType.Application.Json)
            setBody("""{"managerPin":"1234","proposalId":"${proposal.s("proposalId")}","changeIds":["c1","c2"]}""")
        }.bodyAsText())
        assertEquals("2", apply.s("applied"))
        assertEquals("Poutine (Pommes mit Käse und Bratensoße)", transaction { Translations.get(Translations.ITEM, "poutine", "de") })
        assertEquals("Entradas", transaction { Translations.get(Translations.CATEGORY, "starters", "es") })
        val poutine = Json.parseToJsonElement(manager.get("/items").bodyAsText()).jsonArray.map { it.jsonObject }
            .first { it.s("id") == "poutine" }
        assertEquals("Poutine (Pommes mit Käse und Bratensoße)", poutine["names"]!!.jsonObject.s("de"))
        val entry = Json.parseToJsonElement(manager.get("/menu-ai/history").bodyAsText()).jsonArray.single().jsonObject
        assertEquals("translate", entry.s("source"))

        val revert = manager.post("/menu-ai/history/${apply.s("changeSetId")}/revert") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234"}""")
        }
        assertEquals(HttpStatusCode.OK, revert.status, revert.bodyAsText())
        assertNull(transaction { Translations.get(Translations.ITEM, "poutine", "de") })
        assertNull(transaction { Translations.get(Translations.CATEGORY, "starters", "es") })
    }

    @Test
    fun theKeyIsNeverLoggedOrReturned() = testApplication {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        try {
            store(FakeMenuProvider())
            val manager = loginClient()
            val status = manager.get("/menu-ai/status").bodyAsText()
            assertFalse(status.contains(key))
            manager.post("/menu-ai/chat") {
                contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234","text":"hello"}""")
            }
            assertFalse(appender.list.any { it.formattedMessage.contains(key) })
            assertFalse(on().toString().contains(key))
        } finally {
            root.detachAppender(appender)
        }
    }
}
