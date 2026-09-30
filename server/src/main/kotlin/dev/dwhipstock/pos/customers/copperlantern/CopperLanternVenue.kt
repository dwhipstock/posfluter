package dev.dwhipstock.pos.customers.copperlantern

/**
 * Which Copper Lantern store this process runs (fictional, Raleigh, North
 * Carolina; the brand began in Montréal, see [CopperLanternRaleighMove]). Selected
 * at startup with `POS_VENUE` (`vieux-port` | `plateau` | `express`; default `vieux-port`),
 * never hardcoded: it picks the display name and the first-boot seed (menu,
 * floor plan, receipt header). The cloud venue id is NOT taken from here — a
 * store's API key resolves to its venue server-side.
 */
enum class CopperLanternVenue(
    val id: String,
    val displayName: String,
    /**
     * Receipt header for a freshly seeded store (owner-editable afterwards).
     * A fictional street ("Lantern Row") and 555-01xx phone numbers, which
     * are reserved for fiction, so neither can belong to a real business.
     */
    val address: String,
    val phone: String,
) {
    /** The full-service pub (the tablet). Its id stays "vieux-port" so device configs keep working. */
    VIEUX_PORT("vieux-port", "Copper Lantern — Glenwood South", "412 Lantern Row, Raleigh, NC 27601", "(919) 555-0142"),
    PLATEAU("plateau", "Copper Lantern — Plateau", "430 Lantern Row, Raleigh, NC 27601", "(919) 555-0187"),
    /** The quick-serve counter: no tables, numbered orders, self-order kiosks ([CopperLanternExpressSeed]). */
    EXPRESS("express", "Copper Lantern — Express", "418 Lantern Row, Raleigh, NC 27601", "(919) 555-0163");

    val quickServe: Boolean get() = this == EXPRESS

    companion object {
        /** Blank → the pub (vieux-port, shown as Glenwood South); an unknown id fails fast rather than seeding the wrong store. */
        fun of(id: String?): CopperLanternVenue {
            val wanted = id?.trim()?.lowercase().orEmpty()
            if (wanted.isEmpty()) return VIEUX_PORT
            return entries.firstOrNull { it.id == wanted }
                ?: throw IllegalArgumentException("POS_VENUE='$id' — expected one of ${entries.map { it.id }}")
        }

        fun fromEnv(): CopperLanternVenue = of(System.getenv("POS_VENUE"))
    }
}
