plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    application
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("markera.server.ServerKt")
    applicationName = "server"
}

dependencies {
    implementation("io.ktor:ktor-server-netty:3.2.2")
    implementation("io.ktor:ktor-server-content-negotiation:3.2.2")
    implementation("io.ktor:ktor-server-status-pages:3.2.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.2.2")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("com.auth0:java-jwt:4.5.0")
    implementation("com.auth0:jwks-rsa:0.22.2")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.2.2")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.2.2")
}

tasks.test { useJUnitPlatform() }
