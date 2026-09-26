package dev.dwhipstock.pos_client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.IBinder
import android.util.Log
import dev.dwhipstock.pos.module
import dev.dwhipstock.pos.sdk.CashRounding
import dev.dwhipstock.pos.sdk.ReceiptPrintMode
import dev.dwhipstock.pos.sdk.StaffAppMfa
import dev.dwhipstock.pos.sdk.StripeConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** Keeps the local store reachable to staff/customer phones while the POS is backgrounded. */
class TabletStoreService : Service() {
    companion object {
        @Volatile var lastFailure: String? = null
            private set
    }

    private val started = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("tablet-store", "Local POS service",
            NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = Notification.Builder(this, channel.id)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            // per build (POS_BRAND): each brand's app shows its own notification
            .setContentTitle(BuildConfig.SERVICE_TITLE)
            .setContentText("Local ordering is available")
            .setOngoing(true)
            .build()
        startForeground(1042, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started.compareAndSet(false, true)) {
            lastFailure = null
            Thread({ runStore() }, "tablet-store").apply { isDaemon = true }.start()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runStore() {
        try {
            val storeDir = File(filesDir, "store").apply { mkdirs() }
            val dbFile = File(storeDir, "pos.db")
            importStagedStore(dbFile)
            applyStagedCloudSettings(dbFile)
            val cloud = Properties()
            val cloudFile = File(filesDir, "store-cloud.properties")
            if (cloudFile.exists()) cloudFile.inputStream().use(cloud::load)
            // The side-by-side debug copy must not be found by the working POS
            // or push a second copy of the same store history to the cloud.
            val isolatedTest = packageName.endsWith(".embeddedtest")
            val expectedInstallId = cloud.getProperty("store.installId")
            val actualInstallId = if (dbFile.exists()) runCatching {
                SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT value FROM sync_state WHERE key='install_id'", null).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }
            }.getOrNull() else null
            val cloudReady = !isolatedTest &&
                !cloud.getProperty("cloud.url").isNullOrBlank() &&
                !cloud.getProperty("cloud.apiKey").isNullOrBlank() &&
                !expectedInstallId.isNullOrBlank() &&
                actualInstallId == expectedInstallId
            if (cloudFile.exists() && !cloudReady && !isolatedTest) {
                Log.w("TabletStore", "Cloud sync disabled: store identity or provisioning is incomplete")
            }
            val receiptPrintMode = readReceiptPrintMode()
            val stripeConfig = readStripeConfig()
            val storeProps = readStoreProperties()
            // which store this tablet is: store.venue in store.properties wins;
            // unset → this build's own store (POS_BRAND: Copper Lantern → none
            // = Vieux-Port, as always; Sage & Poppy → sage-poppy)
            val venueId = storeProps?.getProperty("store.venue")?.trim()?.takeIf { it.isNotEmpty() }
                ?: BuildConfig.DEFAULT_VENUE.takeIf { it.isNotEmpty() }
            val legalAge = storeProps?.getProperty("legal.age")?.trim()?.toIntOrNull()
            // cash.rounding=nickel|off; unset → nickel
            val cashRounding = CashRounding.resolve(storeProps?.getProperty(CashRounding.KEY), "store.properties")
            cashRounding.warning?.let { Log.w("TabletStore", "Cash rounding config ignored: $it") }
            if (venueId != null) Log.i("TabletStore", "Store: $venueId (port ${BuildConfig.STORE_PORT})")
            val staffAppMfa = readStaffAppMfa(storeProps)
            embeddedServer(CIO, host = if (isolatedTest) "127.0.0.1" else "0.0.0.0", port = BuildConfig.STORE_PORT) {
                module(
                    dbPath = dbFile.absolutePath,
                    // table QRs / staff-app links carry this app's own port
                    lanPort = BuildConfig.STORE_PORT,
                    receiptsDir = File(storeDir, "receipts").absolutePath,
                    billsDir = File(storeDir, "bills").absolutePath,
                    photosDir = File(storeDir, "photos").absolutePath,
                    // A fresh standalone tablet keeps its local demo catalog
                    // across restarts. Only a provisioned cloud store starts
                    // empty and pulls its catalog from the portal.
                    seedMode = if (cloudReady) "none" else "copperlantern",
                    cloudSyncUrl = if (cloudReady) cloud.getProperty("cloud.url") else null,
                    cloudSyncApiKey = if (cloudReady) cloud.getProperty("cloud.apiKey") else null,
                    reportingPortalUrl = if (cloudReady) cloud.getProperty("portal.url") else null,
                    physicalPrinterEnabled = !isolatedTest,
                    staffAppMfa = staffAppMfa,
                    receiptPrintMode = receiptPrintMode,
                    stripeConfig = stripeConfig,
                    venueId = venueId,
                    legalAgeOverride = legalAge,
                    cashRounding = cashRounding,
                )
            }.start(wait = true)
        } catch (error: Throwable) {
            Log.e("TabletStore", "Embedded store failed to start", error)
            lastFailure = "${error.javaClass.simpleName}: ${error.message ?: "startup failed"}"
            started.set(false)
        }
    }

    /**
     * `print.receipts=paper|digital` from the external files dir
     * (/sdcard/Android/data/<package>/files/store.properties), so it can be
     * changed with `adb push` on a release build (scripts/tablet-print-mode.sh).
     * Read once per store start. Missing or bad file → paper; never fails startup.
     */
    private fun readReceiptPrintMode(): ReceiptPrintMode.Resolved {
        val resolved = runCatching {
            ReceiptPrintMode.fromFile(getExternalFilesDir(null)?.let { File(it, "store.properties") })
        }.getOrElse { ReceiptPrintMode.Resolved(ReceiptPrintMode.PAPER, "default", it.message) }
        resolved.warning?.let { Log.w("TabletStore", "Receipt printing config ignored: $it") }
        Log.i("TabletStore", "Receipt printing: ${resolved.mode.wire} (${resolved.source})")
        return resolved
    }

    /**
     * `staff.app.mfa=on|off` in the external store.properties
     * (scripts/tablet-staff-mfa.sh). Unset there → the demo APK's baked-in
     * `staff.app.mfa.required=false` (copperlantern-demo.properties, only in a
     * POS_DEMO_BUILD) → on. A bad value → on with a warning; never fails startup.
     */
    private fun readStaffAppMfa(storeProps: java.util.Properties?): StaffAppMfa.Resolved {
        val fromStore = StaffAppMfa.resolve(storeProps?.getProperty(StaffAppMfa.KEY), "store.properties")
        val resolved = if (fromStore.source != "default" || fromStore.warning != null) fromStore else {
            val demoOff = runCatching {
                Properties().apply { assets.open("copperlantern-demo.properties").use(::load) }
                    .getProperty("staff.app.mfa.required")?.trim()?.toBooleanStrictOrNull() == false
            }.getOrDefault(false)
            if (demoOff) StaffAppMfa.Resolved(StaffAppMfa.OFF, "demo build") else fromStore
        }
        resolved.warning?.let { Log.w("TabletStore", "Staff app MFA config ignored: $it") }
        Log.i("TabletStore", resolved.describe())
        return resolved
    }

    /**
     * The external store.properties itself, for the store switches that are
     * plain values: `store.venue`, `legal.age`, `cash.rounding` and `staff.app.mfa`. Missing/unreadable → null;
     * never fails startup.
     */
    private fun readStoreProperties(): java.util.Properties? = runCatching {
        val file = getExternalFilesDir(null)?.let { File(it, "store.properties") }
        if (file == null || !file.exists()) null
        else java.util.Properties().apply { file.inputStream().use(::load) }
    }.getOrNull()

    /**
     * Optional "Card (Stripe)" tender, TEST MODE only: `stripe.secretKey=sk_test_…`
     * (and optional `stripe.locationId`) in the same external store.properties
     * (scripts/tablet-stripe-config.sh). Anything but an sk_test_ key is refused.
     * Missing/bad → Stripe off; never fails startup; the key is never logged.
     */
    private fun readStripeConfig(): StripeConfig.Resolved {
        val resolved = runCatching {
            StripeConfig.fromFile(getExternalFilesDir(null)?.let { File(it, "store.properties") })
        }.getOrElse { StripeConfig.OFF }
        Log.i("TabletStore", resolved.describe())
        return resolved
    }

    /**
     * ADB can stage a one-time backup in this app's external files directory,
     * but cannot write the release app's private files. Validate both pieces
     * before installing either; never replace a store that has already started.
     */
    private fun importStagedStore(dbFile: File) {
        val staging = getExternalFilesDir("migration") ?: return
        val stagedDb = File(staging, "pos.db")
        val stagedConfig = File(staging, "store-cloud.properties")
        val stagedMedia = File(staging, "store-media.zip")
        if (!stagedDb.exists() && !stagedConfig.exists() && !stagedMedia.exists()) return
        // cloud settings alone re-point an existing store (applyStagedCloudSettings)
        if (!stagedDb.exists() && !stagedMedia.exists()) return
        check(!dbFile.exists()) { "Store import refused: this tablet already has a database" }
        check(stagedDb.isFile && stagedConfig.isFile && stagedMedia.isFile) {
            "Store import needs database, media archive, and cloud settings"
        }
        val mediaNames = listOf("photos", "receipts", "bills")

        val config = Properties().apply { stagedConfig.inputStream().use(::load) }
        val expectedId = config.getProperty("store.installId")?.takeIf { it.isNotBlank() }
        val cloudUrl = config.getProperty("cloud.url")?.takeIf { it.startsWith("https://") }
        check(expectedId != null && cloudUrl != null && !config.getProperty("cloud.apiKey").isNullOrBlank()) {
            "Store import cloud settings are incomplete"
        }
        SQLiteDatabase.openDatabase(stagedDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Store backup failed integrity check" }
            }
            db.rawQuery("SELECT value FROM sync_state WHERE key='install_id'", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == expectedId) {
                    "Store backup identity does not match cloud settings"
                }
            }
        }

        val configFile = File(filesDir, "store-cloud.properties")
        check(!configFile.exists()) { "Store import refused: cloud settings already exist" }
        val dbTemp = File(dbFile.parentFile, "pos.importing")
        val configTemp = File(filesDir, "store-cloud.importing")
        try {
            stagedDb.inputStream().use { input -> dbTemp.outputStream().use(input::copyTo) }
            stagedConfig.inputStream().use { input -> configTemp.outputStream().use(input::copyTo) }
            check(mediaNames.none { File(dbFile.parentFile, it).exists() }) {
                "Store import refused: media already exists"
            }
            mediaNames.forEach { File(dbFile.parentFile, it).mkdirs() }
            var extractedBytes = 0L
            ZipInputStream(stagedMedia.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    check(!name.startsWith('/') && !name.contains('\\') &&
                        name.substringBefore('/') in mediaNames &&
                        name.split('/').none { it == "." || it == ".." }) { "Invalid media archive entry" }
                    val destination = File(dbFile.parentFile, name).canonicalFile
                    val mediaRoot = File(dbFile.parentFile, name.substringBefore('/')).canonicalPath
                    check(destination.path == mediaRoot && entry.isDirectory ||
                        destination.path.startsWith(mediaRoot + File.separator)) {
                        "Invalid media archive path"
                    }
                    if (entry.isDirectory) destination.mkdirs()
                    else {
                        destination.parentFile?.mkdirs()
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val size = zip.read(buffer)
                                if (size < 0) break
                                extractedBytes += size
                                check(extractedBytes <= 500_000_000L) { "Media archive too large" }
                                output.write(buffer, 0, size)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            check(configTemp.renameTo(configFile)) { "Could not install cloud settings" }
            check(dbTemp.renameTo(dbFile)) { "Could not install store backup" }
            stagedDb.delete()
            File(staging, "pos.db-shm").delete()
            File(staging, "pos.db-wal").delete()
            stagedConfig.delete()
            stagedMedia.delete()
            Log.i("TabletStore", "Validated store backup imported")
        } catch (error: Throwable) {
            if (!dbFile.exists()) {
                mediaNames.forEach { File(dbFile.parentFile, it).deleteRecursively() }
                configFile.delete()
            }
            throw error
        } finally {
            dbTemp.delete()
            configTemp.delete()
        }
    }

    /**
     * Re-point this store's cloud sync: ADB stages ONLY `store-cloud.properties`
     * (cloud.url, cloud.apiKey, optional portal.url / store.installId) in the
     * migration folder — e.g. the local two-store demo on a Mac
     * (scripts/tablet-cloud-config.sh). Applied before the store starts; the
     * store's data is never touched, only its sync settings:
     *  - an existing store identity is kept (a staged store.installId must match
     *    it); a store that has never synced is REFUSED unless store.installId is
     *    staged explicitly (sync.CloudRepoint) — the refusal is logged and the
     *    store starts normally on its current settings;
     *  - the whole outbox is re-sent from the start (ingest is idempotent by
     *    event id), so a new or wiped cloud shows this store's full history;
     *  - the previous settings are kept as store-cloud.properties.prev.
     * `https://` anywhere, or plain `http://` to a private LAN address only.
     * A bad file is left staged and logged; the store still starts (offline-first).
     */
    private fun applyStagedCloudSettings(dbFile: File) {
        val staging = getExternalFilesDir("migration") ?: return
        val staged = File(staging, "store-cloud.properties")
        if (!staged.isFile || File(staging, "pos.db").exists() || File(staging, "store-media.zip").exists()) return
        try {
            check(dbFile.exists()) { "open the POS once before staging cloud settings" }
            val next = Properties().apply { staged.inputStream().use(::load) }
            val url = next.getProperty("cloud.url")?.trim().orEmpty()
            check(isAllowedCloudUrl(url)) { "cloud.url must be https:// or http:// to a private LAN address" }
            check(!next.getProperty("cloud.apiKey").isNullOrBlank()) { "cloud.apiKey is missing" }
            next.getProperty("portal.url")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                check(isAllowedCloudUrl(it)) { "portal.url must be https:// or http:// to a private LAN address" }
            }
            val configFile = File(filesDir, "store-cloud.properties")
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val existing = db.rawQuery("SELECT value FROM sync_state WHERE key='install_id'", null).use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
                // never invents an identity: a never-synced store needs an explicit id
                val installId = dev.dwhipstock.pos.sync.CloudRepoint.resolveInstallId(
                    existing, next.getProperty("store.installId"))
                db.beginTransaction()
                try {
                    if (existing == null) {
                        db.execSQL("INSERT OR REPLACE INTO sync_state (key, value) VALUES ('install_id', ?)", arrayOf(installId))
                    }
                    // the target cloud may have none of this store's events yet (a new
                    // cloud, or a wiped demo db): re-send from the start — idempotent
                    db.execSQL("DELETE FROM sync_state WHERE key IN ('push_hwm', 'catalog_cursor')")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                next.setProperty("store.installId", installId)
            }
            val temp = File(filesDir, "store-cloud.repointing")
            temp.outputStream().use { next.store(it, "cloud sync settings") }
            if (configFile.exists()) configFile.copyTo(File(filesDir, "store-cloud.properties.prev"), overwrite = true)
            check(temp.renameTo(configFile)) { "could not install cloud settings" }
            staged.delete()
            Log.i("TabletStore", "Cloud sync re-pointed to $url")
        } catch (refused: dev.dwhipstock.pos.sync.CloudRepoint.Refused) {
            Log.e("TabletStore", "REFUSED to re-point cloud sync: ${refused.message}. " +
                "The staged settings were left in place; the store starts on its current settings.")
        } catch (error: Throwable) {
            Log.w("TabletStore", "Staged cloud settings not applied: ${error.message}")
        }
    }

    private fun isAllowedCloudUrl(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        return when (uri.scheme) {
            "https" -> true
            "http" -> isPrivateIpv4(host)
            else -> false
        }
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168)
    }
}
