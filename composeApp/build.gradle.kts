import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.triplet.play)
    alias(libs.plugins.sentry)
}

kotlin {
    androidTarget()

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            binaryOption("bundleId", "se.kjellstrand.markera")
            // The SQLDelight native driver needs the system sqlite at app link time.
            linkerOpts("-lsqlite3")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.compose.ui.backhandler)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.core)
        }
        // The local series cache runs on a real (in-memory) SQLite in the JVM tests.
        androidUnitTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.onnxruntime.android)
            implementation(libs.mlkit.text.recognition)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.play.services.auth)
            implementation(libs.googleid)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sentry.android)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
    }
}

compose.resources {
    packageOfResClass = "se.kjellstrand.markera.res"
    generateResClass = always
}

sqldelight {
    databases {
        create("MarkeraDb") { packageName.set("se.kjellstrand.markera.series.db") }
    }
}

// The Markera series backend (see PLAN.md). Override with -Pmarkera.backend.url=...
val backendUrl = (project.findProperty("markera.backend.url") as String?)
    ?: "https://markera.duckdns.org"

// Google OAuth *Web* client id, used as `serverClientId` for Credential Manager.
// Not in version control: put `markera.google.client.id=...` in local.properties.
// Empty means "not configured" — sign-in then fails loudly.
val localProps: Properties? = rootProject.file("local.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
val googleClientId: String = localProps?.getProperty("markera.google.client.id") ?: ""

// Sentry error reporting (see `diag/ErrorLog.kt`). `markera.sentry.dsn=...` in local.properties;
// empty means "not configured" and the SDK is never started.
val sentryDsn: String = localProps?.getProperty("markera.sentry.dsn") ?: ""

// Release (upload) signing: `keystore.properties` + `keystore` at the repo root, both
// gitignored (see PLAN.md task 26). Absent → the release build stays unsigned.
val keystoreProps: Properties? = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "se.kjellstrand.markera"
    // Play symbolises native crashes only when the AAB carries the .so symbol tables;
    // extracting them needs an NDK that is actually installed (AGP's default may not be).
    ndkVersion = "29.0.13113456"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "se.kjellstrand.markera"
        minSdk = 24
        targetSdk = 36
        versionCode = 12
        versionName = "1.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"$googleClientId\"")
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        keystoreProps?.let { props ->
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            // The prebuilt .so files are stripped, but their dynamic symbol tables are
            // enough for Play to symbolise native crashes (and to drop its
            // "no debug symbols uploaded" warning). Needs an NDK installed.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Must match the Kotlin/Android jvmTarget (21, from the toolchain): with
    // buildConfig = true there is now a Java source (BuildConfig.java), and AGP
    // rejects a Java/Kotlin target mismatch.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// ContinuousScanReplayTest reads -Dwatch.frames=<dir>; Gradle does not pass -D on
// to the forked test JVM, so forward it (and show the replay's println output).
tasks.withType<Test>().configureEach {
    val frames = providers.systemProperty("watch.frames")
    if (frames.isPresent) {
        systemProperty("watch.frames", frames.get())
        testLogging.showStandardStreams = true
    }
}

// Only here to give Sentry the R8 mapping, so release stack traces are readable. Every release
// build is stamped with a mapping UUID; the upload itself needs `sentry.properties` at the repo
// root (gitignored: auth.token, defaults.org, defaults.project). Without it nothing is uploaded,
// and the mapping can still be sent later with `sentry-cli upload-proguard --uuid <uuid>`.
// The SDK itself is the plain dependency above, started by hand in MarkeraApplication.
sentry {
    val configured = rootProject.file("sentry.properties").exists()
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(configured)
    uploadNativeSymbols.set(false)
    includeSourceContext.set(false)
    includeDependenciesReport.set(false)
    tracingInstrumentation.enabled.set(false)
    autoInstallation.enabled.set(false)
    telemetry.set(false)
}

play {
    serviceAccountCredentials.set(rootProject.file("play-account.json"))
    track.set("internal")
    defaultToAppBundles.set(true)
}

dependencies {
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
