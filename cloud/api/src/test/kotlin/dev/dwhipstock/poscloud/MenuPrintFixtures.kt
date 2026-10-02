package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.menu.MenuCategoryDto
import dev.dwhipstock.poscloud.menu.MenuItemDto
import dev.dwhipstock.poscloud.menu.MenuResponse
import dev.dwhipstock.poscloud.menu.MenuSpecialInput
import dev.dwhipstock.poscloud.menu.MenuVariantDto
import dev.dwhipstock.poscloud.menuai.GeneratedImage
import dev.dwhipstock.poscloud.menuai.ImageGenException
import dev.dwhipstock.poscloud.menuai.ImageProvider
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/** A pub's menu for the printed-menu tests: specials, day-only items, sizes, names in five languages. */
object MenuPrintFixtures {
    private fun v(id: String, en: String, fr: String, cents: Long, i: Int = 0, de: String? = null) =
        MenuVariantDto(id, fr, en, cents, i, listOfNotNull(de?.let { "de" to it }).toMap())

    private fun item(
        id: String, en: String, fr: String, cat: String, sizes: List<MenuVariantDto>, desc: String = "", descFr: String = "",
        alcohol: Boolean = false, days: List<String>? = null, specials: List<MenuSpecialInput>? = null, de: String? = null,
        active: Boolean = true, photo: Long? = null,
    ) = MenuItemDto(id, fr, en, descFr, desc, cat, null, alcohol, active, photo, sizes, "vieux-port",
        names = listOfNotNull(de?.let { "de" to it }).toMap(), availableDays = days, specials = specials)

    val menu = MenuResponse(
        categories = listOf(
            MenuCategoryDto("starters", "Entrées", "Starters", 0, mapOf("de" to "Vorspeisen")),
            MenuCategoryDto("burgers", "Burgers", "Burgers", 1, mapOf("de" to "Burger")),
            MenuCategoryDto("mains", "Plats", "Mains", 2, mapOf("de" to "Hauptgerichte")),
            MenuCategoryDto("desserts", "Desserts", "Desserts", 3, mapOf("de" to "Nachspeisen")),
            MenuCategoryDto("beer", "Bières", "Beer", 4, mapOf("de" to "Bier")),
            MenuCategoryDto("wine", "Vins", "Wine", 5, mapOf("de" to "Wein")),
            MenuCategoryDto("soft", "Sans alcool", "Soft drinks", 6, mapOf("de" to "Alkoholfrei")),
        ),
        items = listOf(
            item("wings", "Chicken Wings", "Ailes de poulet", "starters", listOf(v("wings:6", "6 wings", "6 ailes", 1295, 0, "6 Stück"), v("wings:12", "12 wings", "12 ailes", 2295, 1, "12 Stück")),
                "Crispy wings tossed in house hot sauce, blue cheese dip.", "Ailes croustillantes, sauce piquante maison.",
                specials = listOf(MenuSpecialInput(listOf("wed"), null, null, "Wing Wednesday", mapOf("wings:6" to 895L))), de = "Hähnchenflügel"),
            item("nachos", "Loaded Nachos", "Nachos garnis", "starters", listOf(v("nachos:r", "Regular", "Régulier", 1450)),
                "Tortilla chips, cheddar, jalapeños, pico de gallo, sour cream.", de = "Nachos mit Käse"),
            item("soup", "Soup of the Day", "Soupe du jour", "starters", listOf(v("soup:r", "Bowl", "Bol", 795)), "Ask your server.", de = "Tagessuppe"),
            item("burger", "Pub Burger", "Burger du pub", "burgers", listOf(v("burger:r", "Regular", "Régulier", 1695)),
                "Two smashed patties, aged cheddar, pickles, house sauce, brioche bun.", "Deux galettes, cheddar vieilli, cornichons.",
                specials = listOf(MenuSpecialInput(listOf("tue"), null, null, null, mapOf("burger:r" to 995L))), de = "Pub-Burger"),
            item("veggie", "Garden Burger", "Burger végé", "burgers", listOf(v("veggie:r", "Regular", "Régulier", 1595)),
                "Black bean and quinoa patty, avocado, sprouts.", de = "Gemüseburger"),
            item("fish", "Fish & Chips", "Fish and chips", "mains", listOf(v("fish:1", "One piece", "Un morceau", 1795, 0, "Ein Stück"), v("fish:2", "Two pieces", "Deux morceaux", 2395, 1, "Zwei Stück")),
                "Beer-battered haddock, hand-cut fries, coleslaw, tartar sauce.", de = "Backfisch mit Pommes"),
            item("rib", "Prime Rib", "Côte de bœuf", "mains", listOf(v("rib:r", "10 oz", "10 oz", 3495)),
                "Slow-roasted, au jus, horseradish, mashed potatoes.", days = listOf("fri", "sat"), de = "Hochrippe"),
            item("schnitzel", "Pork Schnitzel", "Escalope de porc", "mains", listOf(v("schnitzel:r", "Regular", "Régulier", 2195)),
                "Crispy breaded pork, lemon, warm potato salad.", de = "Schweineschnitzel"),
            item("cake", "Sticky Toffee Pudding", "Pouding au caramel", "desserts", listOf(v("cake:r", "Slice", "Pointe", 995)),
                "Warm date cake, toffee sauce, vanilla ice cream.", de = "Dattelkuchen mit Karamell"),
            item("lager", "House Lager", "Lager maison", "beer", listOf(v("lager:pint", "Pint", "Pinte", 825, 0, "Pint"), v("lager:pitcher", "Pitcher", "Pichet", 2400, 1, "Krug")),
                "Crisp and clean, brewed for us down the road.", alcohol = true,
                specials = listOf(MenuSpecialInput(listOf("mon", "tue", "wed", "thu", "fri"), "16:00", "18:00", "Happy hour", mapOf("lager:pint" to 500L))), de = "Hauslager"),
            item("ipa", "West Coast IPA", "IPA de la côte Ouest", "beer", listOf(v("ipa:pint", "Pint", "Pinte", 950, 0, "Pint")),
                "Piney, bitter, bright citrus.", alcohol = true,
                specials = listOf(MenuSpecialInput(listOf("mon", "tue", "wed", "thu", "fri"), "16:00", "18:00", "Happy hour", mapOf("ipa:pint" to 650L)))),
            item("red", "House Red", "Rouge maison", "wine", listOf(v("red:glass", "Glass", "Verre", 1100, 0, "Glas"), v("red:bottle", "Bottle", "Bouteille", 3900, 1, "Flasche")),
                "Medium-bodied, cherry and spice.", alcohol = true, de = "Hauswein rot"),
            item("cola", "Cola", "Cola", "soft", listOf(v("cola:r", "Glass", "Verre", 350)), de = "Cola"),
            item("lemonade", "House Lemonade", "Limonade maison", "soft", listOf(v("lemonade:r", "Glass", "Verre", 450)), "Fresh-squeezed, not too sweet.", de = "Hauslimonade"),
            // never printed: switched off at the till
            item("gone", "Retired Stew", "Ragoût retiré", "mains", listOf(v("gone:r", "Bowl", "Bol", 1500)), active = false),
        ),
    )

    /** A small test picture (a gradient), as JPEG bytes. */
    fun picture(w: Int = 512, h: Int = 512, a: Color = Color(200, 120, 60), b: Color = Color(40, 70, 90)): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { paint = GradientPaint(0f, 0f, a, w.toFloat(), h.toFloat(), b); fillRect(0, 0, w, h); dispose() }
        return ByteArrayOutputStream().use { ImageIO.write(img, "jpg", it); it.toByteArray() }
    }

    /** An image service for tests: counts calls, can fail, refuse or stall. */
    class FakeImages(var mode: String = "ok", val stallMs: Long = 0) : ImageProvider {
        override val id = "fake-images"
        override val model = "fake-image-model"
        val calls = AtomicInteger()
        val prompts = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun generate(prompt: String): GeneratedImage = generate(prompt, 1024, 1024)
        override fun enhance(photo: ByteArray, contentType: String, prompt: String) = generate(prompt)
        override fun generate(prompt: String, width: Int, height: Int): GeneratedImage {
            calls.incrementAndGet(); prompts += prompt
            if (stallMs > 0) Thread.sleep(stallMs)
            return when (mode) {
                "fail" -> throw ImageGenException(503, ImageGenException.UNAVAILABLE, "down")
                "refuse" -> throw ImageGenException.refused("fake")
                "junk" -> GeneratedImage("not a picture".toByteArray(), "image/jpeg")
                else -> GeneratedImage(picture(minOf(width, 640), minOf(height, 640)), "image/jpeg")
            }
        }
    }
}
