package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.menu.MenuCategoryDto
import dev.dwhipstock.poscloud.menu.MenuItemDto
import dev.dwhipstock.poscloud.menu.MenuResponse
import dev.dwhipstock.poscloud.menu.MenuSpecialInput
import dev.dwhipstock.poscloud.menu.MenuVariantDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * A real-size pub menu for the printed-menu tests: the store's demo pub
 * (server/.../customers/copperlantern/CopperLanternSeed.kt, 63 items in 7
 * categories, 34 of them drinks) mirrored into resources/menuprint/pub-menu-63.json,
 * plus the pub's happy hour and a day-only item so today's menu has work to do.
 */
object PubMenuFixture {
    val menu: MenuResponse by lazy {
        val root = Json.parseToJsonElement(
            PubMenuFixture::class.java.getResourceAsStream("/menuprint/pub-menu-63.json")!!.readBytes().decodeToString(),
        ).jsonObject
        fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
        val cats = root["categories"]!!.jsonArray.mapIndexed { i, c ->
            val o = c.jsonObject
            MenuCategoryDto(o.s("id"), o.s("nameFr"), o.s("nameEn"), i)
        }
        val items = root["items"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            val id = o.s("id")
            val variants = o["variants"]!!.jsonArray.mapIndexed { i, v ->
                val vo = v.jsonObject
                MenuVariantDto(vo.s("id"), vo.s("labelFr"), vo.s("labelEn"), vo["priceCents"]!!.jsonPrimitive.long, i)
            }
            MenuItemDto(id, o.s("nameFr"), o.s("nameEn"), o.s("descriptionFr"), o.s("descriptionEn"), o.s("categoryId"), null,
                o["isAlcohol"]!!.jsonPrimitive.boolean, true, null, variants, "vieux-port",
                availableDays = if (id == "steak-frites") listOf("fri", "sat") else null,
                specials = when {
                    variants.any { it.id.endsWith(":pint") } -> listOf(MenuSpecialInput(listOf("mon", "tue", "wed", "thu", "fri"), "16:00", "18:00",
                        "Happy hour", mapOf(variants.first().id to variants.first().priceCents - 200)))
                    id == "wings" -> listOf(MenuSpecialInput(listOf("wed"), null, null, null, mapOf(variants.first().id to 900L)))
                    else -> null
                })
        }
        MenuResponse(cats, items)
    }
}
