plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("markera.server.ServerKt")
    applicationName = "server"
}

dependencies {
    implementation("io.ktor:ktor-server-netty:3.6.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.6.1")
    implementation("io.ktor:ktor-server-status-pages:3.6.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    implementation("com.auth0:java-jwt:4.6.2")
    implementation("com.auth0:jwks-rsa:0.24.2")
    // Jakarta Mail (API + the Angus implementation): the suggestion box's SMTP.
    implementation("org.eclipse.angus:angus-mail:2.0.5")
    runtimeOnly("ch.qos.logback:logback-classic:1.6.8")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.6.1")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.6.1")
}

tasks.test { useJUnitPlatform() }
