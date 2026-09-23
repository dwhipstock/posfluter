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
            .setContentTitle("Copper Lantern POS")
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
            embeddedServer(CIO, host = if (isolatedTest) "127.0.0.1" else "0.0.0.0", port = 8080) {
                module(
                    dbPath = dbFile.absolutePath,
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
                )
            }.start(wait = true)
        } catch (error: Throwable) {
            Log.e("TabletStore", "Embedded store failed to start", error)
            lastFailure = "${error.javaClass.simpleName}: ${error.message ?: "startup failed"}"
            started.set(false)
        }
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
}
