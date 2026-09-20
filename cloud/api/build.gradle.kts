plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("io.ktor.plugin") version "3.0.3"
    application
}

group = "dev.dwhipstock.poscloud"
version = "0.1.0"

application {
    mainClass.set("dev.dwhipstock.poscloud.ApplicationKt")
}

// infra builds FROM this exact artifact name
ktor {
    fatJar {
        archiveFileName.set("api-all.jar")
    }
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
    implementation("io.ktor:ktor-server-status-pages-jvm")
    implementation("io.ktor:ktor-server-forwarded-header-jvm")

    // Persistence
    implementation("org.jetbrains.exposed:exposed-core:0.56.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.56.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.56.0")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Password hashing
    implementation("at.favre.lib:bcrypt:0.10.2")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.13")

    // Tests
    testImplementation("io.ktor:ktor-server-test-host-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
}

kotlin {
    jvmToolchain(17)
}
