package dev.dwhipstock.pos.customers.copperlantern

/**
 * Which Copper Lantern store this process runs (fictional, Montréal). Selected
 * at startup with `POS_VENUE` (`vieux-port` | `plateau`; default `vieux-port`),
 * never hardcoded: it picks the display name and the first-boot seed (menu,
 * floor plan, receipt header). The cloud venue id is NOT taken from here — a
 * store's API key resolves to its venue server-side.
 */
enum class CopperLanternVenue(
    val id: String,
    val displayName: String,
    /** Receipt header for a freshly seeded store (owner-editable afterwards). */
    val address: String,
    val phone: String,
) {
    VIEUX_PORT("vieux-port", "Copper Lantern — Vieux-Port", "47 Lantern Lane, Montréal, QC", "+1 514 555 0142"),
    PLATEAU("plateau", "Copper Lantern — Plateau", "212 Lantern Row, Montréal, QC", "+1 514 555 0187");

    companion object {
        /** Blank → Vieux-Port; an unknown id fails fast rather than seeding the wrong store. */
        fun of(id: String?): CopperLanternVenue {
            val wanted = id?.trim()?.lowercase().orEmpty()
            if (wanted.isEmpty()) return VIEUX_PORT
            return entries.firstOrNull { it.id == wanted }
                ?: throw IllegalArgumentException("POS_VENUE='$id' — expected one of ${entries.map { it.id }}")
        }

        fun fromEnv(): CopperLanternVenue = of(System.getenv("POS_VENUE"))
    }
}
