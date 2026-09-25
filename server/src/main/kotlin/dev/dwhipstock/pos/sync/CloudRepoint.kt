package dev.dwhipstock.pos.sync

/**
 * Identity rule for re-pointing an existing store at another cloud (the tablet's
 * staged `store-cloud.properties`, scripts/tablet-cloud-config.sh). The store's
 * sync identity (`sync_state.install_id`) is what the cloud pins a venue to, so
 * a re-point never invents one:
 *  - a store that has synced keeps its own id (a staged id must match it);
 *  - a store that has NEVER synced (no id) is only re-pointed when the operator
 *    names the id explicitly — otherwise a fresh or wrong database could be
 *    pinned to a venue and overwrite that venue's history on the cloud.
 */
object CloudRepoint {
    class Refused(message: String) : IllegalStateException(message)

    /** The install id to keep for this store, or [Refused] with the reason. */
    fun resolveInstallId(existing: String?, staged: String?): String {
        val have = existing?.trim()?.takeIf { it.isNotEmpty() }
        val want = staged?.trim()?.takeIf { it.isNotEmpty() }
        if (have != null && want != null && have != want)
            throw Refused("store.installId $want does not match this store's install id $have")
        return have ?: want ?: throw Refused(
            "this store has never synced (no install id) — refusing to re-point it; " +
                "stage store.installId explicitly to adopt an identity")
    }
}
