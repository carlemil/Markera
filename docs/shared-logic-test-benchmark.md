# Lightweight vision test boundary benchmark (#709)

Measured on 18 October 2026 in `card/709`, production source revision
`356a9292f4ce73d948673c2adc99083e326f0829`. This is an evidence-only prototype;
no production module, source set, scoring algorithm or platform wiring changed.

## Final matched observations and decision

All times below are seconds; execution order is row order. XML counts include
reused results where task status says FROM-CACHE or UP-TO-DATE. Every edit row
executed its production compile and test task with zero reported failures.

| Invocation | Path | Scope | Wall s | XML tests | Test task |
|---|---|---|---:|---:|---|
| warm | android | full | 6.311 | 69 | UP-TO-DATE |
| warm | jvm | full | 38.231 | 69 | executed |
| unchanged | android | target | 8.648 | 20 | FROM-CACHE |
| unchanged | jvm | target | 7.973 | 20 | executed |
| unchanged | android | full | 5.160 | 69 | FROM-CACHE |
| unchanged | jvm | full | 3.155 | 69 | FROM-CACHE |
| edit1 | android | target | 24.015 | 20 | executed |
| edit1 | jvm | target | 7.825 | 20 | executed |
| edit2 | jvm | target | 6.643 | 20 | executed |
| edit2 | android | target | 35.766 | 20 | executed |
| edit3 | android | target | 33.949 | 20 | executed |
| edit3 | jvm | target | 16.054 | 20 | executed |
| edit1 | android | full | 35.696 | 69 | executed |
| edit1 | jvm | full | 7.835 | 69 | executed |
| edit2 | jvm | full | 4.478 | 69 | executed |
| edit2 | android | full | 27.146 | 69 | executed |
| edit3 | android | full | 32.936 | 69 | executed |
| edit3 | jvm | full | 7.028 | 69 | executed |
| restored | android | full | 9.439 | 69 | FROM-CACHE |
| restored | jvm | full | 4.209 | 69 | FROM-CACHE |

| Edit-cycle scope | Android median | JVM median | Saved | Reduction | 30% and 5 s threshold |
|---|---:|---:|---:|---:|---|
| target | 33.949 s | 7.825 s | 26.124 s | 77.0% | True |
| full | 32.936 s | 7.028 s | 25.908 s | 78.7% | True |

**Go for a separately scoped KMP vision-extraction follow-up**, subject to the
integration and migration constraints below. Both matched scopes clear both
predeclared thresholds. This card does not authorize or implement that extraction.
Warm unchanged runs have much smaller savings and sometimes replay tests; they
are not evidence for the source-edit decision. The 38.231 s JVM warm/setup run
is explicitly excluded from the edit-cycle medians. The 69-test vision scope
does not represent the broader 383-test app suite measured elsewhere.

## Boundary and coverage

The scratch JVM harness references these ten existing production files directly
under `composeApp/src/commonMain/kotlin/se/kjellstrand/markera/vision/`:
`CentreEstimator.kt`, `CentreTypes.kt`, `DetectionPostProcess.kt`, `Detections.kt`,
`FittedEllipse.kt`, `HitScoring.kt`, `ImageOps.kt`, `Otsu.kt`, `RingProbe.kt` and
`TargetPlane.kt`. They depend on Kotlin/math and each other. No production logic
or tests are copied. This follows the existing `:eval` source-reference pattern
without its ONNX/native inference dependencies.

The same six existing common test classes run on both paths:
`CentreEstimatorTest`, `DetectionPostProcessTest`, `HitScoringTest`, `ImageOpsTest`,
`RingProbeTest` and `TargetPlaneTest`. Targeted scope is `HitScoringTest` (20 tests);
full vision scope is all six classes (69 tests). **Full vision is not the full app
suite.** The harness uses `kotlin-test-junit` and JUnit 4, as do these app tests.

`HoleDetector`, `DigitDetector` and `PlatformImage` remain app expect/actual seams.
UI/Compose, SQLDelight persistence, series/networking/serialization and platform
implementations are outside this boundary. Centre remains exclusively the digit
6–9 row-line intersection; the ellipse supplies outline, scale and perspective.

## Environment and method

Windows x64, Intel Core i9-12900K (16 cores/24 logical processors), 127.7 GiB RAM;
Gradle wrapper 9.4.1, Kotlin plugin 2.2.10, AGP 9.2.1. The final matched matrix uses
Java 21 toolchains and daemon criteria for both app and harness, with identical
`-Xmx4g -Dfile.encoding=UTF-8` daemon settings. `gradlew --version` reports an
Oracle 17.0.1 launcher; daemon selection is separately pinned to Java 21. The
shell's unrelated `java` on PATH reports Microsoft 25.0.2 and is not the wrapper
launcher or selected daemon.

Both paths explicitly receive `--build-cache --configuration-cache --console=plain`.
The benchmark branch predates #706's committed cache defaults (master later
contains them); explicit flags remove that default-setting difference. This is
not a rerun of the current integrated master tree. #705's 24.6 s app compilation
and 1.84 s warm reused result are historical context, not matched controls.

The Windows build slot was reserved: no other Tickets card built during this
matrix. Ordinary system activity was not disabled. Timings are stopwatch wall
time around the entire wrapper invocation, including process startup, Gradle
configuration, compilation and tests. No clean, daemon stop or `--rerun-tasks`
was used. Each graph is warmed once, then unchanged targeted/full invocations
are recorded. These can replay cached results and are labelled accordingly.

For each scope, three paired trials append one unique valid unused internal
function to **the same** `HitScoring.kt`, preserving scoring behavior while
changing bytecode. The two paths see identical bytes in each pair. Trial order
alternates Android/JVM, JVM/Android, Android/JVM. A new function name is used for
every scope/trial and final matrix to prevent old build-cache replay. The actual
compile and test tasks execute in every measured edit trial. Test XML counts
alone would not establish fresh execution; task outcomes are also checked.

The original source bytes are restored after each pair and in `finally`, with
SHA256 checks. Restoration refuses to overwrite an unexpected concurrent edit.
Restored SHA256: `AA38C180175A6C47D5DE17C8AACE64481C843D8184242D36291AEF26560B8661`.
The post-restoration full vision invocations may reuse outputs, deliberately.

## Interpretation and migration cost

This microbenchmark measures a tiny source edit in an already warmed graph. It
does not measure cold dependency resolution, cold CI, UI edits, iOS compilation,
inference quality, APK packaging, or total ticket completion time. An unused
function is a reproducible compilation proxy, not a correctness-changing patch.
Three trials are descriptive observations, not a confidence interval. Retain
individual observations so machine noise remains visible.

A real extraction must be a KMP vision module with Android/iOS targets, one
production source of truth, and common tests still executed on relevant targets.
It needs dependency/source-set rewiring in app and eval, visibility/API decisions,
integration verification and maintenance of the boundary. It cannot ship this
scratch JVM harness as a replacement for app/native coverage. Series extraction
has additional SQLDelight, network and platform costs and was not prototyped.
No reliable implementation-effort estimate follows from timing compilation.

Any retained executable migration still requires the full relevant app suite,
Android packaging/device checks where appropriate, and iOS/native checks for
changed shared wiring. Fast targeted iteration never replaces that final gate.

## Reproduction

Use an isolated worktree at the recorded source revision, with existing local
Android SDK configuration available (do not publish local properties or secrets).
Save the following recipe under ignored `build/card-709-matched/run.ps1` and run
it with Windows PowerShell from that worktree. Change `$repo` to its absolute
path. Before every independent rerun, replace the identifier prefix
`benchmark709matched20261018` with a fresh Kotlin-valid nonce; retaining it can
replay cached edited bytecode and test results. Each invocation should be the
only active build on this machine. The recipe creates only ignored harness,
logs and result JSON plus guarded temporary source edits. Keep the console and
logs; any nonzero exit invalidates the matrix and is recorded in results.json.
The first warm pass is setup on already available dependencies, not a cold-build
measurement. Review compile/test outcomes instead of inferring execution from XML.

```powershell
$ErrorActionPreference = 'Stop'
$repo = 'D:/source/Markera.worktrees/card-709'
$scratch = "$repo/build/card-709-matched"
$harness = "$scratch/harness"
New-Item -ItemType Directory -Force $harness | Out-Null
@'
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "vision-benchmark"
'@ | Set-Content "$harness/settings.gradle.kts"
@'
plugins { kotlin("jvm") version "2.2.10" }
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
val repo = providers.gradleProperty("markeraRepo").get()
sourceSets["main"].kotlin {
    srcDir("$repo/composeApp/src/commonMain/kotlin/se/kjellstrand/markera/vision")
    include("CentreEstimator.kt", "CentreTypes.kt", "DetectionPostProcess.kt", "Detections.kt", "FittedEllipse.kt", "HitScoring.kt", "ImageOps.kt", "Otsu.kt", "RingProbe.kt", "TargetPlane.kt")
}
sourceSets["test"].kotlin {
    srcDir("$repo/composeApp/src/commonTest/kotlin/se/kjellstrand/markera/vision")
    include("CentreEstimatorTest.kt", "DetectionPostProcessTest.kt", "HitScoringTest.kt", "ImageOpsTest.kt", "RingProbeTest.kt", "TargetPlaneTest.kt")
}
dependencies { testImplementation(kotlin("test-junit")) }
tasks.withType<Test>().configureEach { useJUnit() }
'@ | Set-Content "$harness/build.gradle.kts"
'org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8' | Set-Content "$harness/gradle.properties"
New-Item -ItemType Directory -Force "$harness/gradle" | Out-Null
Copy-Item "$repo/gradle/gradle-daemon-jvm.properties" "$harness/gradle/gradle-daemon-jvm.properties"
$rows = [System.Collections.Generic.List[object]]::new()
function Run-Bench($path, $scope, $label) {
    $gradleArgs = @('--build-cache', '--configuration-cache', '--console=plain')
    if ($path -eq 'jvm') { $gradleArgs += @('-p', $harness, "-PmarkeraRepo=$repo", 'test') }
    else { $gradleArgs += ':composeApp:testDebugUnitTest' }
    $classes = if ($scope -eq 'target') { @('HitScoringTest') } else { @('CentreEstimatorTest','DetectionPostProcessTest','HitScoringTest','ImageOpsTest','RingProbeTest','TargetPlaneTest') }
    foreach ($class in $classes) { $gradleArgs += @('--tests', "se.kjellstrand.markera.vision.$class") }
    $log = "$scratch/$label-$path-$scope.log"
    $timer = [Diagnostics.Stopwatch]::StartNew()
    & "$repo/gradlew.bat" @gradleArgs *> $log
    $code = $LASTEXITCODE
    $timer.Stop()
    $xmlDir = if ($path -eq 'jvm') { "$harness/build/test-results/test" } else { "$repo/composeApp/build/test-results/testDebugUnitTest" }
    $count = 0
    $failures = 0
    Get-ChildItem "$xmlDir/TEST-*.xml" -ErrorAction SilentlyContinue | ForEach-Object { [xml]$x = Get-Content $_.FullName; $count += [int]$x.testsuite.tests; $failures += [int]$x.testsuite.failures }
    $row = [pscustomobject]@{ label=$label; path=$path; scope=$scope; seconds=[math]::Round($timer.Elapsed.TotalSeconds,3); exit=$code; tests=$count; failures=$failures; outcomes=@(Select-String -Path $log -Pattern '^> Task .*(:test|:compileKotlin|:compileDebugKotlinAndroid)' | ForEach-Object {$_.Line}) }
    $rows.Add($row)
    $rows | ConvertTo-Json -Depth 6 | Set-Content "$scratch/results.json"
    Write-Output ($row | ConvertTo-Json -Compress)
    if ($code -ne 0) { throw "Failed $label $path $scope; see $log" }
}
Set-Location $repo
foreach ($path in @('android','jvm')) { Run-Bench $path full warm }
foreach ($scope in @('target','full')) { foreach ($path in @('android','jvm')) { Run-Bench $path $scope unchanged } }
$source = "$repo/composeApp/src/commonMain/kotlin/se/kjellstrand/markera/vision/HitScoring.kt"
$original = [IO.File]::ReadAllBytes($source)
$originalHash = (Get-FileHash $source).Hash
$utf8 = [Text.UTF8Encoding]::new($false)
$originalText = $utf8.GetString($original)
try {
    foreach ($scope in @('target','full')) {
        foreach ($trial in 1..3) {
            # A valid body edit emits a new unused internal function each time, forcing
            # new bytecode without changing target scoring behavior or assertions.
            $edit = "`r`ninternal fun benchmark709matched20261018${scope}${trial}(): Int = $trial`r`n"
            if ((Get-FileHash $source).Hash -ne $originalHash) { throw 'Concurrent source modification: refusing benchmark edit' }
            [IO.File]::WriteAllText($source, $originalText + $edit, $utf8)
            $expectedHash = (Get-FileHash $source).Hash
            $paths = if ($trial % 2) { @('android','jvm') } else { @('jvm','android') }
            foreach ($path in $paths) { Run-Bench $path $scope "edit$trial" }
            if ((Get-FileHash $source).Hash -ne $expectedHash) { throw 'Concurrent source modification: refusing restoration' }
            [IO.File]::WriteAllBytes($source, $original)
        }
    }
} finally {
    # Do not overwrite a concurrent change. A successful own edit or original is safe.
    $currentHash = (Get-FileHash $source).Hash
    if ($currentHash -eq $expectedHash -or $currentHash -eq $originalHash) { [IO.File]::WriteAllBytes($source, $original) }
    else { throw 'Concurrent edit detected: source left intact for inspection' }
    if ((Get-FileHash $source).Hash -ne $originalHash) { throw 'Source restoration check failed' }
    "Restored SHA256 $originalHash" | Set-Content "$scratch/restoration.txt"
}
foreach ($path in @('android','jvm')) { Run-Bench $path full restored }



```
## Preserved exploratory evidence

The inherited pilot lacked matched configuration-cache settings. A subsequent
exploratory pass matched cache flags but retained Java 17 for the harness versus
Java 21 for the app. Both are excluded from the final decision. Their unmodified
results/logs remain under ignored `build/card-709-develop` and
`build/card-709-controlled`; the definitive results/logs, restoration receipt and
recipe are under `build/card-709-matched`. All three completed without build or
test failures; exploratory source restoration was checked before final trials.
The varying observations illustrate why these exploratory timings must not be
combined with the controlled matrix or treated as a stable machine guarantee.

### Excluded develop edit observations (seconds)

| Scope | Trial | Android | JVM |
|---|---|---:|---:|
| target | 1 | 14.58 | 2.806 |
| target | 2 | 12.179 | 3.012 |
| target | 3 | 13.684 | 2.579 |
| full | 1 | 13.658 | 3.18 |
| full | 2 | 12.98 | 2.961 |
| full | 3 | 12.713 | 3.294 |

### Excluded controlled edit observations (seconds)

| Scope | Trial | Android | JVM |
|---|---|---:|---:|
| target | 1 | 11.307 | 2.171 |
| target | 2 | 14.645 | 2.181 |
| target | 3 | 10.555 | 3.399 |
| full | 1 | 18.365 | 7.118 |
| full | 2 | 36.892 | 7.2 |
| full | 3 | 36.781 | 7.043 |

## Final validation

Only this Markdown report is retained. Production sources match the measured revision; guarded byte restoration passed.
The later master merge adds #706 cache defaults to `gradle.properties`; the
matrix used explicit matching flags before that merge, as described above. There is no
app/backend deployment for this evidence-only change. A repository test-file
reference search for this report returned no matches. Final review uses the
Tickets docs-only rule; the benchmark trials above are measurement evidence,
not a claimed full-app integration run on current master.
