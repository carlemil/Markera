plugins {
    // No version: the Kotlin plugin is already on the build classpath via the
    // root project's kotlin-multiplatform plugin.
    kotlin("jvm")
}

kotlin {
    // Consume the same Java 21 vision artifact as the app; no copied source tree.
    jvmToolchain(21)
}

dependencies {
    implementation(project(":vision"))
    // Desktop ONNX Runtime (bundles the win-x64 native libs).
    implementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
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

// An explicit evaluation is fresh work, even with a fixed seed. Only this task
// reruns: Kotlin compilation and dependency/resource tasks remain incremental.
// Always executing also consumes external model/image changes without stale results.
tasks.register<Test>("mosaic") {
    description = "Run fresh hole-detection inference and write the evaluation mosaic."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("eval.HoleDetectionMosaicTest") }
    systemProperty("mosaic.required", "true")
    outputs.dir(layout.buildDirectory.dir("mosaic"))
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
