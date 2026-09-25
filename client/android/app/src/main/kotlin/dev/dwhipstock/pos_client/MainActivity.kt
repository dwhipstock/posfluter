package dev.dwhipstock.pos_client

import android.bluetooth.BluetoothManager
import android.content.Intent
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
                    // Card (Stripe) diagnostics: `adb logcat -s StripeTerminal`.
                    // The Dart side has already masked any secret.
                    "stripeLog" -> {
                        val message = call.argument<String>("message") ?: ""
                        if (call.argument<Boolean>("warn") == true) Log.w("StripeTerminal", message)
                        else Log.i("StripeTerminal", message)
                        result.success(null)
                    }
                    // device-wide Location / Bluetooth switches (not app permissions)
                    "deviceServices" -> result.success(mapOf(
                        "locationOn" to runCatching {
                            getSystemService(LocationManager::class.java)?.isLocationEnabled
                        }.getOrNull(),
                        "bluetoothOn" to runCatching {
                            getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
                        }.getOrNull(),
                    ))
                    "openSettings" -> {
                        val action = when (call.argument<String>("which")) {
                            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                            else -> Settings.ACTION_SETTINGS
                        }
                        runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { Log.w("StripeTerminal", "could not open $action: ${it.message}") }
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }
}
