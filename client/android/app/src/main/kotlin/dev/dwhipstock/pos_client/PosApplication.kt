package dev.dwhipstock.pos_client

import android.app.Application
import dev.dwhipstock.pos.StoreAssets

/** Makes packaged store resources available before the local service starts. */
class PosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val assetManager = assets
        StoreAssets.install(object : StoreAssets.Source {
            override fun open(path: String) = assetManager.open(path)
            override fun list(path: String) = assetManager.list(path)?.toList() ?: emptyList()
        })
    }
}
