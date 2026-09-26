import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val stageEmbeddedStoreSources by tasks.registering(Sync::class) {
    from("../../../server/src/main/kotlin") {
        exclude("**/DesktopMain.kt", "**/sdk/EscPos.kt", "**/sdk/Images.kt",
            "**/sdk/QrPng.kt", "**/tools/**")
    }
    into(layout.buildDirectory.dir("generated/embeddedStoreSources"))
}

// Demo-only assets are not packaged in an ordinary product build. The local
// staff-app MFA bypass cannot be toggled from the app or a runtime settings UI.
val posDemoBuild = providers.environmentVariable("POS_DEMO_BUILD")
    .map { it.equals("true", ignoreCase = true) }.getOrElse(false)

// The stock app (a phone that counts and receives stock) is the same Flutter
// code built with --dart-define=POS_APP=stock (lib/app_mode.dart). Flutter
// hands the defines to Gradle base64-encoded; read the same switch here so the
// phone app gets its own id and label, portrait, and never starts the store.
val dartDefines: Map<String, String> = (project.findProperty("dart-defines") as String?)
    ?.split(",")
    ?.mapNotNull { encoded ->
        runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
    }
    ?.mapNotNull { pair -> pair.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
    ?.toMap() ?: emptyMap()
val stockApp = dartDefines["POS_APP"] == "stock"

// Which store brand this counter-tablet build is (--dart-define=POS_BRAND=…,
// read in Dart by lib/app_mode.dart). Each brand is its own Android app so both
// can be installed and run on one tablet at once: own application id (so own
// private DB/files and external store.properties), label, launcher icon,
// default venue, embedded-store port and service notification. Copper Lantern
// keeps the original id so the live tablet install upgrades in place.
data class Brand(
    val applicationId: String, val label: String, val icon: String,
    val storePort: Int, val defaultVenue: String,
)
val brands = mapOf(
    "copperlantern" to Brand("dev.dwhipstock.pos_client", "Copper Lantern POS", "@mipmap/ic_launcher",
        8080, ""),
    "sagepoppy" to Brand("dev.dwhipstock.pos_sagepoppy", "Sage & Poppy POS", "@mipmap/ic_launcher_sagepoppy",
        8082, "sage-poppy"),
)
val brandName = dartDefines["POS_BRAND"]?.trim()?.takeIf { it.isNotEmpty() } ?: "copperlantern"
val brand = brands[brandName]
    ?: error("POS_BRAND=$brandName is not one of ${brands.keys.joinToString("|")}")

val sqliteJdbcNative by configurations.creating
val stageSqliteNative by tasks.registering(Sync::class) {
    from({ zipTree(sqliteJdbcNative.singleFile) }) {
        include("org/sqlite/native/Linux-Android/*/libsqlitejdbc.so")
        eachFile {
            val abi = when (relativePath.segments[4]) {
                "aarch64" -> "arm64-v8a"
                "arm" -> "armeabi-v7a"
                "x86" -> "x86"
                "x86_64" -> "x86_64"
                else -> error("unexpected SQLite Android ABI: $relativePath")
            }
            relativePath = org.gradle.api.file.RelativePath(true, abi, "libsqlitejdbc.so")
        }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/sqliteJni"))
}

android {
    namespace = "dev.dwhipstock.pos_client"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        // The stock app installs next to the POS, never over it.
        // (the stock app is one LAN client for either brand; POS_BRAND is ignored)
        applicationId = if (stockApp) "dev.dwhipstock.pos_stock" else brand.applicationId
        // The counter tablet hosts the store; the stock app is a LAN client.
        buildConfigField("boolean", "EMBEDDED_STORE", (!stockApp).toString())
        buildConfigField("int", "STORE_PORT", brand.storePort.toString())
        buildConfigField("String", "DEFAULT_VENUE", "\"${brand.defaultVenue}\"")
        buildConfigField("String", "SERVICE_TITLE", "\"${brand.label}\"")
        manifestPlaceholders["appIcon"] = if (stockApp) "@mipmap/ic_launcher" else brand.icon
        manifestPlaceholders["screenOrientation"] = if (stockApp) "portrait" else "sensorLandscape"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        // Stripe Terminal SDK (Card (Stripe) tender) needs Android 8.0 / API 26+
        minSdk = maxOf(flutter.minSdkVersion, 26)
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        debug {
            // Side-by-side verification must never overwrite the working POS.
            applicationIdSuffix = if (stockApp) ".debug" else ".embeddedtest"
            manifestPlaceholders["appLabel"] = if (stockApp) "Stock Test" else brand.label.replace(" POS", " Test")
        }
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
            // Ktor and SQLite JDBC use reflection and refer to optional JDK
            // APIs absent on Android. Keep the validated runtime intact until
            // Android-specific R8 rules are covered by device tests.
            isMinifyEnabled = false
            isShrinkResources = false
            manifestPlaceholders["appLabel"] = if (stockApp) "Stock" else brand.label
        }
    }

    sourceSets.getByName("main").assets.srcDir("../../../server/src/main/resources")
    if (posDemoBuild) sourceSets.getByName("main").assets.srcDir("src/demo/assets")
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/sqliteJni").get().asFile)
}

tasks.configureEach {
    if (name.startsWith("merge") &&
        (name.endsWith("NativeLibs") || name.endsWith("JniLibFolders"))) {
        dependsOn(stageSqliteNative)
    }
}

kotlin {
    sourceSets.getByName("main").kotlin.srcDir(stageEmbeddedStoreSources)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    val ktorVersion = "3.0.3"
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-compression-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.exposed:exposed-core:0.56.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.56.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.56.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    add(sqliteJdbcNative.name, "org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("com.drewnoakes:metadata-extractor:2.19.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")
}
