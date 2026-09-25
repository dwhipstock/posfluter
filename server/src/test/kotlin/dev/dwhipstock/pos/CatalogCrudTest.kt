package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncOutbox
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogCrudTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun io.ktor.client.HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun io.ktor.client.HttpClient.patchJson(path: String, body: String) =
        patch(path) { contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun catalogMutationsAreManagerSessionGated() = testApplication {
        application { module(dbPath = tempDb()) }
        val server = loginClient(pin = "9999")

        assertEquals(HttpStatusCode.Forbidden, server.postJson("/items",
            """{"nameFr":"g","nameEn":"A","categoryId":"beer","abbrev":"A",
                "variants":[{"labelFr":"bouteille","labelEn":"Bottle","priceCents":10000}]}""").status)
        assertEquals(HttpStatusCode.Forbidden,
            server.patchJson("/items/lantern-lager/variants/lantern-lager:pint", """{"priceCents":11000}""").status)
        assertEquals(HttpStatusCode.Forbidden, server.delete("/items/lantern-lager").status)
        assertEquals(HttpStatusCode.Forbidden,
            server.postJson("/categories", """{"nameFr":"dessert","nameEn":"Dessert"}""").status)
    }

    /** The owner's story: add an item, fix a price, and the catalog reflects it immediately. */
    @Test
    fun itemAndVariantLifecycle() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        // create with two sizes; id is slugged from the EN name
        val created = c.postJson("/items",
            """{"nameFr":"Soupe saisonnière","nameEn":"Seasonal Soup","categoryId":"starters","abbrev":"MM","isAlcohol":false,
                "variants":[{"labelFr":"ordinaire","labelEn":"Regular","priceCents":6000},
                            {"labelFr":"spécial","labelEn":"Special","priceCents":8000}]}""")
        assertEquals(HttpStatusCode.Created, created.status)
        val item = json.parseToJsonElement(created.bodyAsText()).jsonObject
        assertEquals("seasonal-soup", item["id"]!!.jsonPrimitive.content)
        assertEquals(2, item["variants"]!!.jsonArray.size)
        val regularId = item["variants"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("seasonal-soup:regular", regularId)

        // it lists for ordering
        val listed = json.parseToJsonElement(c.get("/items").bodyAsText()).jsonArray
        assertTrue(listed.any { it.jsonObject["id"]!!.jsonPrimitive.content == "seasonal-soup" })

        // price edit takes effect on the next catalog read
        c.patchJson("/items/seasonal-soup/variants/$regularId", """{"priceCents":6500}""")
            .let { assertEquals(HttpStatusCode.OK, it.status) }
        val after = json.parseToJsonElement(c.get("/items").bodyAsText()).jsonArray
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == "seasonal-soup" }.jsonObject
        assertEquals(6500L, after["variants"]!!.jsonArray
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == regularId }
            .jsonObject["priceCents"]!!.jsonPrimitive.long)

        // rename + move category + abbrev
        c.patchJson("/items/seasonal-soup", """{"nameEn":"House Soup","categoryId":"cocktails","abbrev":"MS"}""")
            .let { assertEquals(HttpStatusCode.OK, it.status) }
        val renamed = json.parseToJsonElement(c.get("/items?all=true").bodyAsText()).jsonArray
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == "seasonal-soup" }.jsonObject
        assertEquals("House Soup", renamed["nameEn"]!!.jsonPrimitive.content)
        assertEquals("cocktails", renamed["category"]!!.jsonPrimitive.content)

        // add a third size, then delete it again
        val withTower = c.postJson("/items/seasonal-soup/variants",
            """{"labelFr":"géant","labelEn":"Jumbo","priceCents":12000}""")
        assertEquals(HttpStatusCode.Created, withTower.status)
        assertEquals(3, json.parseToJsonElement(withTower.bodyAsText())
            .jsonObject["variants"]!!.jsonArray.size)
        c.delete("/items/seasonal-soup/variants/seasonal-soup:jumbo")
            .let { assertEquals(HttpStatusCode.OK, it.status) }

        // deleting down to zero sizes is refused
        c.delete("/items/seasonal-soup/variants/seasonal-soup:special")
            .let { assertEquals(HttpStatusCode.OK, it.status) }
        val lastDelete = c.delete("/items/seasonal-soup/variants/$regularId")
        assertEquals(HttpStatusCode.Conflict, lastDelete.status)
        assertTrue("last_variant" in lastDelete.bodyAsText())

        // soft-delete the item: gone from every list, row still in the DB
        c.delete("/items/seasonal-soup").let { assertEquals(HttpStatusCode.OK, it.status) }
        val all = json.parseToJsonElement(c.get("/items?all=true").bodyAsText()).jsonArray
        assertTrue(all.none { it.jsonObject["id"]!!.jsonPrimitive.content == "seasonal-soup" })
        assertEquals(1L, transaction {
            dev.dwhipstock.pos.base.Items.selectAll()
                .count { it[dev.dwhipstock.pos.base.Items.id] == "seasonal-soup" }.toLong()
        })

        // outbox trail
        val types = transaction { SyncOutbox.selectAll().map { it[SyncOutbox.eventType] } }
        for (expected in listOf("item.created", "item.variant_updated", "item.updated",
                "item.variant_added", "item.variant_deleted", "item.deleted")) {
            assertTrue(expected in types, "missing outbox event $expected in $types")
        }
    }

    /** A size on an open bill cannot be deleted out from under the check. */
    @Test
    fun variantOnOpenCheckCannotBeDeleted() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        val checkId = json.parseToJsonElement(c.postJson("/tables/t1/checks", "{}").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$checkId/lines",
            """{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }

        val refused = c.delete("/items/lantern-lager/variants/lantern-lager:pitcher")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("variant_in_use" in refused.bodyAsText())

        // the whole item is blocked too, with its own code (not the size's)
        val itemRefused = c.delete("/items/lantern-lager")
        assertEquals(HttpStatusCode.Conflict, itemRefused.status)
        assertTrue("item_in_use" in itemRefused.bodyAsText())

        // void the check → deletion goes through
        c.postJson("/checks/$checkId/void", """{"reason":"Tester le système","managerPin":"1234"}""")
            .let { assertEquals(HttpStatusCode.OK, it.status) }
        c.delete("/items/lantern-lager/variants/lantern-lager:pitcher")
            .let { assertEquals(HttpStatusCode.OK, it.status) }

        // ordering the deleted size now 404s; the surviving sizes still work
        assertEquals(HttpStatusCode.Created, c.postJson("/tables/t2/checks", "{}").status)
        val newCheck = json.parseToJsonElement(c.get("/zones").bodyAsText()).jsonArray
        assertTrue(newCheck.isNotEmpty()) // sanity
        val open = json.parseToJsonElement(c.postJson("/tables/t2/checks", "{}").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int
        assertEquals(HttpStatusCode.NotFound, c.postJson("/checks/$open/lines",
            """{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1}""").status)
        assertEquals(HttpStatusCode.Created, c.postJson("/checks/$open/lines",
            """{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":1}""").status)
    }

    @Test
    fun categoryLifecycleAndReorder() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        // create lands at the end of the sort order
        val created = c.postJson("/categories", """{"nameFr":"dessert","nameEn":"Dessert"}""")
        assertEquals(HttpStatusCode.Created, created.status)
        val dessert = json.parseToJsonElement(created.bodyAsText()).jsonObject
        assertEquals("dessert", dessert["id"]!!.jsonPrimitive.content)

        // rename
        c.patchJson("/categories/dessert", """{"nameEn":"Desserts"}""")
            .let { assertEquals(HttpStatusCode.OK, it.status) }

        // reorder: dessert first
        val ids = json.parseToJsonElement(c.get("/categories").bodyAsText()).jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        val reordered = listOf("dessert") + ids.filter { it != "dessert" }
        c.patchJson("/categories/order",
            """{"orderedIds":[${reordered.joinToString(",") { "\"$it\"" }}]}""")
            .let { assertEquals(HttpStatusCode.OK, it.status) }
        val afterOrder = json.parseToJsonElement(c.get("/categories").bodyAsText()).jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals("dessert", afterOrder.first())

        // a category with live items refuses deletion; empty one deletes
        val catRefused = c.delete("/categories/beer-cider")
        assertEquals(HttpStatusCode.Conflict, catRefused.status)
        assertTrue("category_not_empty" in catRefused.bodyAsText())
        assertEquals(HttpStatusCode.OK, c.delete("/categories/dessert").status)
        val finalIds = json.parseToJsonElement(c.get("/categories").bodyAsText()).jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue("dessert" !in finalIds)
    }
}
