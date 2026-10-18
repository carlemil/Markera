# Build performance baseline

Measured 2026-10-18, 22:29–22:35 Europe/Stockholm, source commit
`f8c6e0a280c2d12fe441b3a22a2fbac364b2dc2a` (#705). No performance settings changed.
The isolated worktree initially had clean tracked sources; only the new profiling
runner was untracked. The main checkout's user-owned `AGENTS.md` was left alone.
Other Markera agents paused Windows builds for the entire timed matrix.

## Environment and state

Windows: Core i9-12900K, 24 logical CPUs, 127.75 GiB RAM; Gradle 9.4.1;
launcher Java 17.0.1, daemon Java 21 selected by `gradle-daemon-jvm.properties`;
project Kotlin plugin 2.2.10 (Gradle's embedded Kotlin 2.3.0 is different),
AGP 9.2.1, Compose 1.9.0, compile SDK 36.1, target SDK 36, min SDK 24.
Configuration cache was already enabled. Model and local SDK configuration were
copied into ignored files from the main checkout; neither was changed there.

**Forced is not a cold machine.** Each forced row used `--rerun-tasks`, existing
dependency caches and a reusable daemon; no `clean`, daemon stop or cache deletion.
The first test row additionally had no local outputs or configuration-cache entry
in this new worktree. Assemble and gate forced rows ran later with local outputs
present. Their different states prevent treating them as comparable cold trials.
No `FROM-CACHE` task appeared in any run. Warm rows reused local outputs and the
configuration cache; tests reported up-to-date, so these measure the unchanged
verification gate, not fresh execution of all test bodies.

## Windows wall times

Seconds from process invocation to exit; every exit code was zero. Three warm
repetitions, in execution order, with an ordinary median (no discarded outliers).

| Scenario | Forced | Warm 1 | Warm 2 | Warm 3 | Warm median |
|---|---:|---:|---:|---:|---:|
| `:composeApp:testDebugUnitTest` | 82.230 | 2.518 | 1.840 | 1.694 | **1.840** |
| `:composeApp:assembleDebug` | 34.802 | 2.481 | 2.873 | 3.233 | **2.873** |
| Both tasks in one invocation | 64.310 | 2.648 | 3.125 | 3.052 | **3.052** |

| Combined gate after an edit | Wall seconds | What actually ran |
|---|---:|---|
| One Kotlin file: `ManualHit.kt`, equivalent float spelling `20f` → `20.0f` | 27.962 | App Kotlin compile 24.619s; unchanged bytecode kept tests/package up-to-date |
| Restore Kotlin original bytes | 27.107 | App Kotlin compilation again |
| Compose `strings.xml`: `Detect` → `Detect target` | 37.455 | Resource generation, Kotlin compile, tests and package |
| Final gate after exact resource restoration | 16.048 | Recompile, tests and package; original sources restored |

Both originals were restored and byte equality checked in `finally` blocks.
The Kotlin edit is a small source edit with equivalent output, **not** a measured
ABI or behavioral change. Edit rows are single observations, not medians.
The final JVM XML reports contain 383 tests across 43 suites, zero failures/errors.

## Local Gradle profile breakdown

Seconds from generated `--profile` HTML. Compile includes task names matching
`compile`, `ksp` or `generateCommonMainMarkeraDb`; package/dex includes `dex`,
`packageDebug`, `compressDebug` or `mergeDebugAssets`. Test is the unit-test task.
These are inclusive task-duration sums, including up-to-date checks; parallel
work overlaps, so **columns and task totals must not be added to obtain wall time**.
Configuration is the profile's “Configuring Projects”, not all startup overhead.

| Run | Configuration | Compile sum | Test task | Package/dex sum | All task sum | UP-TO-DATE |
|---|---:|---:|---:|---:|---:|---:|
| Test forced | 6.756 | 58.791 | 5.267 | 0.503 | 79.230 | 3 |
| Assemble forced | 0.608 | 24.021 | — | 25.626 | 71.980 | 3 |
| Gate forced | 1.373 | 50.524 | 6.151 | 27.893 | 106.200 | 4 |
| Gate warm 3 | 0 | 0.090 | 0.015 | 0.072 | 0.408 | 62 |
| Kotlin edit | 0 | 24.672 | 0.019 | 0.106 | 25.144 | 61 |
| Resource edit | 0 | 26.571 | 7.746 | 2.449 | 38.252 | 45 |
| Final restored gate | 0 | 9.435 | 4.117 | 1.002 | 15.463 | 45 |

Test warm rows had 40 up-to-date tasks each; assemble warm rows 55; gate warm
rows 62. Every warm/edit row reported configuration-cache reuse. All 16 Windows
invocations generated local profiles. No scan was uploaded.

## iOS simulator build

Mac mini: Apple M4, 24 GiB RAM, macOS 26.5, Xcode 26.6 (17F113), Gradle 9.4.1;
launcher Java 26.0.2.1, daemon selection Java 21. Read-only SSH probing found
237 GiB available and a clean shared checkout. An archive of the same source
commit was extracted to `/tmp/markera-card705-20261018`, with derived data at
`/tmp/markera-card705-20261018-derived`. Only the model was copied from `~/eval`.
No shared checkout sync/reset, simulator installation or launch was performed.

Built with `xcodegen generate -q`, then:

```sh
/usr/bin/time -p xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/markera-card705-20261018-derived build
```

First isolated-output build: **86.06s**; unchanged repeats: **2.98, 1.32, 1.30s**;
median **1.32s**. All succeeded. Existing machine dependency/Swift-package caches
and daemon were retained. The first Gradle framework phase reported 66s and built
both arm64 and x64 simulator frameworks. Xcode warns the Kotlin framework script
runs every build because dependency analysis is disabled. No fine-grained Xcode
timing summary or Gradle profile was collected inside that Xcode phase, so these
wall timings cannot establish which individual iOS task dominates or whether
avoiding x64 would improve the usual named-device build. A generic destination
may build more architectures than the normal deployment recipe.

## Ranked observations and next experiments

1. **Source recompilation dominates the measured edit cycle.** App Kotlin compile
   alone was 24.619s for the tiny Kotlin edit and 24.633s for the resource edit.
   Investigate smaller shared-logic compilation/test boundaries (#709); benchmark
   the changed-source cycle, since unchanged gates are already around 3s.
2. **Forced rebuilding discards useful local state.** Forced combined gate was
   64.310s versus a 3.052s unchanged median. Forced compilation consumed 50.524
   aggregate task seconds and dex/package 27.893. Remove redundant forced gates
   only where verification rules allow (#708), then measure actual savings.
3. **Fresh worktrees lose local outputs/configuration state.** The first forced
   test compiled Kotlin for 43.294s plus unit-test Kotlin for 8.357s; test execution
   was 5.267s. Test shared build-cache reuse across worktrees (#706). Zero observed
   cache hits does not measure what an enabled/populated build cache could save.
4. **Test bodies are smaller than compilation here.** Full test task execution
   was 5.267–7.746s in these executed runs, including worker/report overhead.
   Historical class durations cannot stand in for gate wall time.
5. **Warm iOS invocation is cheap; fresh outputs are expensive.** The 86.06s versus
   1.32s median supports preserving isolated build outputs, but does not prove a
   particular framework/Xcode optimization. Profile the standard arm64 destination
   before changing its build graph.

These are observations from one machine/session, not promised speedups or clean
dependency-download timings. Samples were not randomized; background OS activity,
thermal state and reused compiler daemons can affect results.

## Reproduce

From a disposable checkout with the documented SDK/model prerequisites, reserve
an exclusive build window and run PowerShell:

```powershell
./scripts/profile-builds.ps1
# Narrow reruns, retaining state:
./scripts/profile-builds.ps1 -Scenario Gate -SkipForced
```

The runner writes ignored `build/profiling/<timestamp>/` logs, environment,
`runs.json`, `medians.json`, restoration checks and HTML profiles; original Gradle
profiles also remain in `build/reports/profile`. `-Scenario` selects Test,
Assemble, Gate or Edits. At least three warm runs are enforced. No app setting or
dependency cache is changed. Failure stops the matrix, records the exit code and
restores an active edited source before propagating the error.
For edit scenarios the tracked checkout must be clean. Original source bytes are
also backed up in the output directory. If a source changes concurrently, the
runner preserves its current bytes and reports the backup path for reconciliation.
Output directories must be within this checkout's `build/`. Local raw evidence for this run is
`build/profiling/baseline/`; it is intentionally not committed.

Method: [Gradle performance guide](https://docs.gradle.org/current/userguide/performance.html).
