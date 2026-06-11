import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
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
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.material.icons.extended)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.onnxruntime.android)
            implementation(libs.mlkit.text.recognition)
        }
    }
}

android {
    namespace = "se.kjellstrand.markera"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "se.kjellstrand.markera"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // The frame source is swapped at build time per flavor:
    //   camera → live CameraX preview (real device)
    //   mock   → random images bundled from the hole dataset (emulator)
    // Build/install the emulator variant with e.g. :composeApp:installMockDebug.
    flavorDimensions += "source"
    productFlavors {
        create("camera") {
            dimension = "source"
            isDefault = true
        }
        create("mock") {
            dimension = "source"
            applicationIdSuffix = ".mock"
            versionNameSuffix = "-mock"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// ---- Mock camera frames ----------------------------------------------------
// Copies a random sample of dataset images into the `mock` flavor's generated
// assets (under fake_frames/) plus an index.txt the app reads at runtime.
// Override defaults with -Pmock.frames.count, -Pmock.frames.seed, and
// -Pmock.frames.dirs (";"-separated absolute dirs). Bump the seed to refresh
// the bundled pool.
abstract class PrepareMockFramesTask : DefaultTask() {
    @get:Input abstract val count: Property<Int>
    @get:Input abstract val seed: Property<Long>
    @get:Input abstract val maxDim: Property<Int>
    @get:Input abstract val sourceDirs: ListProperty<String>

    // Wired to the mock variant's generated assets dir by the Variant API.
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val framesDir = File(outputDir.get().asFile, "fake_frames")
        framesDir.deleteRecursively()
        framesDir.mkdirs()
        val exts = setOf("jpg", "jpeg", "png", "webp", "bmp")
        val all = sourceDirs.get().flatMap { d ->
            val f = File(d)
            if (f.isDirectory) f.listFiles()?.toList().orEmpty() else emptyList()
        }.filter { it.isFile && it.extension.lowercase() in exts }
        if (all.isEmpty()) {
            logger.warn(
                "prepareMockFrames: no images found in ${sourceDirs.get()} — " +
                    "the mock flavor will show a blank preview."
            )
        }
        val picked = all.shuffled(Random(seed.get())).take(count.get())
        val limit = maxDim.get()
        val index = StringBuilder()
        picked.forEachIndexed { i, src ->
            // The model only ever sees 1280 px, so downscale large dataset
            // photos to keep the mock APK small. Re-encode to JPEG, or fall
            // back to copying the original if it can't be decoded.
            val name = if (downscaleToJpeg(src, File(framesDir, "frame_%03d.jpg".format(i)), limit)) {
                "frame_%03d.jpg".format(i)
            } else {
                "frame_%03d.%s".format(i, src.extension.lowercase())
                    .also { src.copyTo(File(framesDir, it), overwrite = true) }
            }
            index.appendLine(name)
        }
        File(framesDir, "index.txt").writeText(index.toString())
        logger.lifecycle("prepareMockFrames: bundled ${picked.size} of ${all.size} dataset images (≤${limit}px)")
    }

    private fun downscaleToJpeg(src: File, dst: File, maxDim: Int): Boolean = try {
        val img = ImageIO.read(src)
        if (img == null) {
            false
        } else {
            val scale = minOf(1.0, maxDim.toDouble() / maxOf(img.width, img.height))
            val w = maxOf(1, (img.width * scale).toInt())
            val h = maxOf(1, (img.height * scale).toInt())
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(img, 0, 0, w, h, null)
            g.dispose()
            ImageIO.write(out, "jpg", dst)
            true
        }
    } catch (t: Throwable) {
        logger.warn("prepareMockFrames: could not transcode ${src.name}, copying as-is", t)
        false
    }
}

val mockFramesCount = (project.findProperty("mock.frames.count") as String?)?.toIntOrNull() ?: 25
val mockFramesSeed = (project.findProperty("mock.frames.seed") as String?)?.toLongOrNull() ?: 1234L
val mockFramesMaxDim = (project.findProperty("mock.frames.maxdim") as String?)?.toIntOrNull() ?: 1600
val mockFramesDirsProp = (project.findProperty("mock.frames.dirs") as String?)
    ?: "D:/ml/holes/dataset/images/val;D:/ml/holes/dataset/images/train"

val prepareMockFrames = tasks.register<PrepareMockFramesTask>("prepareMockFrames") {
    count.set(mockFramesCount)
    seed.set(mockFramesSeed)
    maxDim.set(mockFramesMaxDim)
    sourceDirs.set(mockFramesDirsProp.split(";", ",").map { it.trim() }.filter { it.isNotEmpty() })
}

// Feed the generated frames into the mock flavor's assets via the Variant API,
// which also carries the task dependency automatically.
extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants(selector().withFlavor("source" to "mock")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            prepareMockFrames,
            PrepareMockFramesTask::outputDir,
        )
    }
}

dependencies {
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    testImplementation(libs.junit)
}
