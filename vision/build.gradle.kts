plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(21)
    androidTarget()
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

// Same transitional AGP/KMP compatibility flags as composeApp (gradle.properties).
// No Android APIs, resources, Compose or inference runtime belong in this module.
android {
    namespace = "se.kjellstrand.markera.vision"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
