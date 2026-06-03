plugins {
    // No version: the Kotlin plugin is already on the build classpath via the
    // root project's kotlin-multiplatform plugin.
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

// Reuse the *real* pure-Kotlin vision pipeline (post-processing, calibration,
// ellipse fit, hit scoring, …) straight from commonMain so this evaluation
// harness exercises the same code the app runs. Only HoleDetector.kt is an
// expect/actual (onnxruntime-android); we exclude it and provide a desktop
// ONNX Runtime detector + the RawDetection/Detection data classes instead.
sourceSets["main"].kotlin {
    srcDir("../composeApp/src/commonMain/kotlin/se/kjellstrand/markera/vision")
    exclude("**/HoleDetector.kt")
}

dependencies {
    // Desktop ONNX Runtime (bundles the win-x64 native libs).
    implementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    // Nine full-resolution (12 MP) images plus annotated RGB copies blow past
    // the default worker heap.
    maxHeapSize = "2g"
    // Forward the -Dmosaic.* command-line properties into the forked test JVM
    // (Gradle does not propagate -D system properties to test workers). Uses
    // providers so it stays configuration-cache friendly.
    for (key in listOf("mosaic.seed", "mosaic.images", "mosaic.model")) {
        val value = providers.systemProperty(key)
        if (value.isPresent) systemProperty(key, value.get())
    }
    // Surface println() output (chosen files, scores, mosaic path) live.
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
