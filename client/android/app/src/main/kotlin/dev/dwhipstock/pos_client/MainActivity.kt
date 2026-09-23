package dev.dwhipstock.pos_client

import android.content.Intent
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, TabletStoreService::class.java))
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "dev.dwhipstock.pos_client/store")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startupFailure" -> result.success(TabletStoreService.lastFailure)
                    "restart" -> {
                        startForegroundService(Intent(this, TabletStoreService::class.java))
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }
}
