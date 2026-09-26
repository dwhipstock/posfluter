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
    /**
     * Receipt header for a freshly seeded store (owner-editable afterwards).
     * Fictional streets; the postal codes use the letters Q and U, which
     * Canada Post never assigns, so they cannot belong to a real address.
     */
    val address: String,
    val phone: String,
) {
    VIEUX_PORT("vieux-port", "Copper Lantern — Vieux-Port", "47, rue de la Lanterne, Montréal (Québec) H2Y 1Q7", "+1 514 555 0142"),
    PLATEAU("plateau", "Copper Lantern — Plateau", "212, avenue du Lampion, Montréal (Québec) H2J 3U4", "+1 514 555 0187");

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
