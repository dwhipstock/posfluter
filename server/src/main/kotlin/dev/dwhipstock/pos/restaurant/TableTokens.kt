package dev.dwhipstock.pos.restaurant

import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.util.Base64

/**
 * Unguessable customer links (033). Every dining table carries a random opaque
 * token, and the ONLY way a guest reaches a table's menu, bill or order submit
 * is `/m/t/{token}`. Internal ids ("t5") and floor-plan numbers (/m/lower/8)
 * are trivially guessable, so they no longer resolve for customers. A manager
 * can rotate a token so an old slip stops working. Tokens are generated on the
 * store (SecureRandom), never fetched, and never synced to the cloud.
 */
object TableTokens {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** 128 random bits, URL-safe base64 (22 chars). */
    fun newToken(): String = ByteArray(16).also(random::nextBytes).let(encoder::encodeToString)

    /** Shape check before touching the db: 22 chars of the URL-safe alphabet. */
    fun looksValid(token: String): Boolean = token.length == 22 && token.all {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
    }

    fun menuPath(token: String): String = "/m/t/$token"

    /** Live table id for a customer token, or null (unknown, rotated, or deleted). Call in a transaction. */
    fun tableIdFor(token: String): String? {
        if (!looksValid(token)) return null
        return DiningTables.selectAll()
            .where { (DiningTables.publicToken eq token) and DiningTables.deletedAt.isNull() }
            .firstOrNull()?.get(DiningTables.id)
    }

    /** Give any table without a token one (e.g. rows written by raw SQL). Call in a transaction. */
    fun ensureAll() {
        DiningTables.selectAll().where { DiningTables.publicToken.isNull() }
            .map { it[DiningTables.id] }
            .forEach { id -> DiningTables.update({ DiningTables.id eq id }) { it[publicToken] = newToken() } }
    }

    /** Replace a table's token; the old link stops working at once. Call in a transaction. */
    fun rotate(tableId: String): String {
        val token = newToken()
        DiningTables.update({ DiningTables.id eq tableId }) { it[publicToken] = token }
        return token
    }
}
