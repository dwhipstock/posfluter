plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("io.ktor.plugin") version "3.0.3"
    application
}

group = "dev.dwhipstock.pos"
version = "0.1.0"

application {
    mainClass.set("dev.dwhipstock.pos.DesktopMainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("io.ktor:ktor-server-call-logging-jvm")
    implementation("io.ktor:ktor-server-compression-jvm")
    implementation("io.ktor:ktor-server-status-pages-jvm")
    implementation("io.ktor:ktor-server-cors-jvm")

    // QR generation for table codes (M3 scan-to-order)
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    // Persistence
    implementation("org.jetbrains.exposed:exposed-core:0.56.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.56.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.56.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // PIN hashing (M5 security basics)
    implementation("at.favre.lib:bcrypt:0.10.2")

    // EXIF orientation for uploaded item photos (normalize on upload)
    implementation("com.drewnoakes:metadata-extractor:2.19.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.13")

    // Tests
    testImplementation("io.ktor:ktor-server-test-host-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
}

kotlin {
    jvmToolchain(17)
}

// The stand-alone card terminal simulator (scripts/demo-terminal.sh): a pretend
// countertop reader on this machine that the tablet POS reaches over the LAN.
tasks.register<JavaExec>("runTerminal") {
    group = "application"
    description = "Run the card terminal simulator (TERMINAL_PORT, default 8090)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.dwhipstock.pos.tools.TerminalSimulatorMainKt")
    standardInput = System.`in`
}
