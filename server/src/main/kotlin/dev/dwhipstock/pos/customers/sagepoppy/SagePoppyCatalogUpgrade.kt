package dev.dwhipstock.pos.customers.sagepoppy

import dev.dwhipstock.pos.api.writeChunkedCatalogSnapshot
import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.sync.CloudSync
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

/**
 * The seed-version step that brings a RUNNING Sage & Poppy store up to the
 * current [SagePoppyCatalog]: a store first seeded with the ~50 hand-written
 * products gets the rest of the ~5,000-product shelf, once.
 *
 * Additive only, and never over anyone's work:
 * - a generated product is added only when neither its id nor its barcode is
 *   already in the store (a manager who added that code first keeps theirs);
 * - the hand-written products keep their names, prices, barcodes and every
 *   other edit — they only gain the new facets (brand, subcategory, size) and
 *   a demo weight, which no one could have set before this step existed;
 * - products a manager added at the counter are never read or written;
 * - missing categories are created; existing ones are not renamed or moved.
 *
 * A store that has already synced sends the additions up as chunked
 * `catalog.snapshot` events (the cloud upserts them). One that never synced
 * needs nothing: its first sync snapshots the whole shelf anyway.
 */
object SagePoppyCatalogUpgrade {
    const val STATE_KEY = "sage_poppy_catalog_version"
    private val log = LoggerFactory.getLogger(SagePoppyCatalogUpgrade::class.java)

    data class Result(val added: Int, val skippedBarcodeTaken: Int, val facetsFilled: Int, val alreadyCurrent: Boolean)

    /** Record that this store already has the current catalog (a fresh seed). Inside a transaction. */
    fun markCurrent() = SyncState.set(STATE_KEY, SagePoppyCatalog.VERSION.toString())

    fun upgradeIfNeeded(): Result = transaction {
        val have = SyncState.get(STATE_KEY)?.toIntOrNull() ?: 0
        if (have >= SagePoppyCatalog.VERSION) return@transaction Result(0, 0, 0, alreadyCurrent = true)

        SagePoppySeed.Cat.entries.forEachIndexed { i, c ->
            Categories.insertIgnore { it[id] = c.id; it[sortOrder] = 100 + i; it[nameFr] = c.en; it[nameEn] = c.en }
        }

        // the hand-written products: facets + weight where still unset, nothing else
        val weights = SagePoppyCatalog.products.associate { it.id to it }
        val touched = mutableListOf<String>()
        for ((id, facets) in SagePoppyCatalog.curatedFacets) {
            val row = Items.selectAll().where { Items.id eq id }.firstOrNull() ?: continue
            if (row[Items.subcategory] != null) continue
            Items.update({ (Items.id eq id) and Items.subcategory.isNull() }) {
                it[brand] = facets.first
                it[subcategory] = facets.second
                it[sizeLabel] = facets.third
                if (row[Items.salesWeight] == 0) it[salesWeight] = weights[id]?.salesWeight ?: 0
            }
            if (row[Items.deletedAt] == null) touched += id
        }

        // the generated products: only where neither the id nor the barcode is taken
        val takenIds = HashSet<String>()
        val takenCodes = HashSet<String>()
        Items.selectAll().forEach { r ->
            takenIds += r[Items.id]
            if (r[Items.deletedAt] == null) r[Items.barcode]?.let { takenCodes += it }
        }
        var skipped = 0
        val toAdd = SagePoppyCatalog.generated.filter { p ->
            if (p.id in takenIds) return@filter false
            val code = p.shelfCode
            if (code != null && code in takenCodes) { skipped++; return@filter false }
            true
        }
        toAdd.chunked(500).forEach { SagePoppySeed.insertProducts(it) }

        val synced = SyncState.get(CloudSync.CATALOG_SNAPSHOT_SEQ) != null
        if (synced && (toAdd.isNotEmpty() || touched.isNotEmpty())) {
            writeChunkedCatalogSnapshot(toAdd.map { it.id } + touched, reason = "catalog_v${SagePoppyCatalog.VERSION}")
        }
        markCurrent()
        log.info("Sage & Poppy catalog v${SagePoppyCatalog.VERSION}: added ${toAdd.size} products, " +
            "filled facets on ${touched.size}, skipped $skipped (barcode already in use)")
        Result(toAdd.size, skipped, touched.size, alreadyCurrent = false)
    }
}
