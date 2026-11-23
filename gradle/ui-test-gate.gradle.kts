// UI-test upload gate (issue #15): no store upload until AppUiTest has passed on the phone
// against the exact commit being shipped (CLAUDE.md, "Release rule: UI tests before every upload").
//
// Applied from composeApp/build.gradle.kts. Three tasks:
//  - uiTestGateStart   runs before connectedDebugAndroidTest; notes HEAD, whether the tree is
//                      clean and the start time in build/ui-test-gate/start.txt.
//  - recordUiTestPass  finalizes connectedDebugAndroidTest. When the run was the whole AppUiTest
//                      class (`-Pandroid.testInstrumentationRunnerArguments.class=…AppUiTest`), every
//                      fresh result XML has zero failures/errors/skips, and the tree was clean with the
//                      same HEAD before and after, it writes `.ui-test-pass` at the repo root (gitignored):
//                      line 1 is the commit sha, the rest is informational. A failing AppUiTest run
//                      deletes it.
//  - checkUiTestGate   a dependency of every Play `publish*` task: fails unless `.ui-test-pass` names
//                      HEAD and the working tree is clean.
// The Mac side (mac-beta.sh, mac-metadata.sh, the Fastfile's upload lanes) checks the same record
// with scripts/ui-gate.sh; scripts/mac-sync.sh carries it over.
//
// Emergency override: -Pmarkera.skipUiGate=true or SKIP_UI_GATE=1 skips the check with a warning.
//
// Configuration cache: the tasks hold only files and strings; git runs at execution time.

object UiGate {
    const val TEST_CLASS = "se.kjellstrand.markera.AppUiTest"
    const val RECORD_NAME = ".ui-test-pass"

    fun git(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args)).directory(dir).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw GradleException("git ${args.joinToString(" ")} failed: $out")
        return out.trim()
    }

    fun head(dir: File) = git(dir, "rev-parse", "HEAD")
    fun dirtyFiles(dir: File) = git(dir, "status", "--porcelain").lines().filter { it.isNotBlank() }.map { it.trim() }

    fun banner(lines: List<String>): String {
        val bar = "!".repeat(78)
        return (listOf(bar) + lines.map { "!! $it" } + bar).joinToString("\n")
    }
}

abstract class UiTestGateStart : DefaultTask() {
    @get:Internal abstract val repoDir: DirectoryProperty
    @get:Internal abstract val marker: RegularFileProperty

    @TaskAction
    fun run() {
        val dir = repoDir.get().asFile
        val file = marker.get().asFile
        file.parentFile.mkdirs()
        val dirty = UiGate.dirtyFiles(dir)
        file.writeText("${UiGate.head(dir)}\nclean=${dirty.isEmpty()}\nstart=${System.currentTimeMillis()}\n")
    }
}

abstract class RecordUiTestPass : DefaultTask() {
    @get:Internal abstract val repoDir: DirectoryProperty
    @get:Internal abstract val marker: RegularFileProperty
    @get:Internal abstract val resultsDir: DirectoryProperty
    @get:Internal abstract val record: RegularFileProperty
    @get:Internal abstract val runnerClass: Property<String>

    @TaskAction
    fun run() {
        val dir = repoDir.get().asFile
        val recordFile = record.get().asFile
        val markerFile = marker.get().asFile
        if (!markerFile.isFile) {
            logger.lifecycle("UI gate: no start marker, so no record written.")
            return
        }
        val start = markerFile.readLines()
        val startSha = start.first().trim()
        val startClean = start.any { it == "clean=true" }
        val startMillis = start.firstOrNull { it.startsWith("start=") }?.removePrefix("start=")?.toLongOrNull() ?: Long.MAX_VALUE
        markerFile.delete()

        // Only results this run wrote: an earlier run's XML must never count.
        val fresh = resultsDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".xml") && it.lastModified() >= startMillis }
            .toList()
        val texts = fresh.map { it.readText() }
        val cases = texts.sumOf { Regex("""<testcase\b[^>]*classname="${Regex.escape(UiGate.TEST_CLASS)}"""").findAll(it).count() }
        if (cases == 0) {
            logger.lifecycle("UI gate: this run had no ${UiGate.TEST_CLASS} results; ${UiGate.RECORD_NAME} unchanged.")
            return
        }
        val problems = mutableListOf<String>()
        texts.forEachIndexed { i, text ->
            for (attr in listOf("failures", "errors", "skipped")) {
                Regex("""<testsuites?\b[^>]*\b$attr="(\d+)"""").findAll(text)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }
                    .filter { it > 0 }
                    .forEach { problems += "${fresh[i].name}: $attr=$it" }
            }
            if (Regex("""<(failure|error|skipped)\b""").containsMatchIn(text)) problems += "${fresh[i].name}: a test did not pass"
        }
        if (problems.isNotEmpty()) {
            recordFile.delete()
            logger.warn(UiGate.banner(listOf("AppUiTest FAILED: store uploads stay blocked (${UiGate.RECORD_NAME} removed).") + problems.distinct()))
            return
        }
        val reasons = mutableListOf<String>()
        val cls = runnerClass.getOrElse("")
        if (cls != UiGate.TEST_CLASS) reasons += "the run was not the whole class (runner arg class='$cls', needs '${UiGate.TEST_CLASS}')"
        val endSha = UiGate.head(dir)
        val endDirty = UiGate.dirtyFiles(dir)
        if (!startClean) reasons += "the working tree was dirty when the run started"
        if (endDirty.isNotEmpty()) reasons += "the working tree is dirty now: ${endDirty.take(5).joinToString(", ")}"
        if (startSha != endSha) reasons += "HEAD moved during the run ($startSha -> $endSha)"
        if (reasons.isNotEmpty()) {
            logger.warn("UI gate: AppUiTest passed, but no record was written, because:\n" + reasons.joinToString("\n") { "  - $it" })
            return
        }
        val devices = fresh.map { it.name.removePrefix("TEST-").removeSuffix(".xml") }
        recordFile.writeText(
            "$endSha\nclass=${UiGate.TEST_CLASS}\ntests=$cases\ndevices=${devices.joinToString(" | ")}\n" +
                "recorded=${java.time.Instant.now()}\n"
        )
        logger.lifecycle("UI gate: AppUiTest passed ($cases tests) on $endSha; uploads of this commit are allowed.")
    }
}

abstract class CheckUiTestGate : DefaultTask() {
    @get:Internal abstract val repoDir: DirectoryProperty
    @get:Internal abstract val record: RegularFileProperty
    @get:Internal abstract val skip: Property<Boolean>

    @TaskAction
    fun run() {
        if (skip.get()) {
            logger.warn(UiGate.banner(listOf(
                "UI-TEST GATE SKIPPED (markera.skipUiGate / SKIP_UI_GATE).",
                "This upload is NOT covered by a passing on-phone AppUiTest run.",
                "Emergencies only: run AppUiTest on the phone as soon as possible.",
            )))
            return
        }
        val dir = repoDir.get().asFile
        val recordFile = record.get().asFile
        val head = UiGate.head(dir)
        val dirty = UiGate.dirtyFiles(dir)
        val recorded = recordFile.takeIf { it.isFile }?.readLines()?.firstOrNull()?.trim()
        val problems = buildList {
            when {
                recorded == null -> add("no passing AppUiTest run is recorded (${recordFile.name} is missing)")
                recorded != head -> add("the recorded AppUiTest pass is for $recorded, but HEAD is $head")
            }
            if (dirty.isNotEmpty()) add("the working tree has uncommitted changes: ${dirty.take(5).joinToString(", ")}")
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Upload refused: the UI tests have not passed on the phone for exactly this source.\n" +
                    problems.joinToString("\n") { "  - $it" } + "\n" +
                    "Commit everything, then run on the phone:\n" +
                    "  \$env:ANDROID_SERIAL=\"<phone serial>\"; .\\gradlew.bat :composeApp:connectedDebugAndroidTest " +
                    "\"-Pandroid.testInstrumentationRunnerArguments.class=${UiGate.TEST_CLASS}\"\n" +
                    "Emergency override (loud warning): -Pmarkera.skipUiGate=true"
            )
        }
        logger.lifecycle("UI gate: AppUiTest passed on $head; upload allowed.")
    }
}

val uiGateRepo = rootProject.layout.projectDirectory
val uiGateRecord = uiGateRepo.file(UiGate.RECORD_NAME)
val uiGateMarker = layout.buildDirectory.file("ui-test-gate/start.txt")

val uiTestGateStart = tasks.register<UiTestGateStart>("uiTestGateStart") {
    description = "Notes HEAD and tree state before an instrumented run (UI-test upload gate)."
    repoDir.set(uiGateRepo)
    marker.set(uiGateMarker)
    outputs.upToDateWhen { false }
}

val recordUiTestPass = tasks.register<RecordUiTestPass>("recordUiTestPass") {
    description = "Records HEAD in .ui-test-pass when a whole, clean AppUiTest run passed."
    repoDir.set(uiGateRepo)
    marker.set(uiGateMarker)
    resultsDir.set(layout.buildDirectory.dir("outputs/androidTest-results/connected"))
    record.set(uiGateRecord)
    runnerClass.set(providers.gradleProperty("android.testInstrumentationRunnerArguments.class").orElse(""))
    outputs.upToDateWhen { false }
}

val checkUiTestGate = tasks.register<CheckUiTestGate>("checkUiTestGate") {
    group = "verification"
    description = "Fails unless .ui-test-pass names HEAD and the tree is clean (before any Play upload)."
    repoDir.set(uiGateRepo)
    record.set(uiGateRecord)
    skip.set(
        providers.gradleProperty("markera.skipUiGate").map { it.equals("true", ignoreCase = true) }
            .orElse(providers.environmentVariable("SKIP_UI_GATE").map { it == "1" || it.equals("true", ignoreCase = true) })
            .orElse(false)
    )
    outputs.upToDateWhen { false }
}

tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(uiTestGateStart)
    finalizedBy(recordUiTestPass)
}
// Every Gradle Play Publisher upload (publishReleaseBundle, publishReleaseListing, publishApps, ...).
// promote* only moves an already uploaded build between tracks and is not gated.
tasks.matching { it.name.startsWith("publish") }.configureEach { dependsOn(checkUiTestGate) }
