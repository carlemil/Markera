# Tickets verification in Markera

Use this map during development to select tests that cover the changed behavior.
Tests are under `composeApp/src/commonTest/kotlin/se/kjellstrand/markera/` unless
stated otherwise. Include changed tests and callers affected by the change; this
is a starting map, not permission to omit relevant coverage.

| Changed area | Development tests |
|---|---|
| `vision/` geometry, scoring, image operations | Matching `vision/*Test` classes; geometry changes include `CentreEstimatorTest`, `RingProbeTest`, `TargetPlaneTest`, `HitScoringTest` and `ui/markera/ManualHitTest` |
| Detection postprocessing | `vision/DetectionPostProcessTest`; use the eval skill for model/detection quality changes |
| `series/` DTOs, recording, export, statistics | Matching `series/*Test` and `series/stats/*Test`; persistence also includes `SeriesRepositoryTest`, `SeriesMigrationTest`, `LoginMigrationTest` in `androidUnitTest` |
| `webshooter/`, competition wizard | `webshooter/*Test`, `ui/competition/MarkingWizardViewModelTest`, Android DTO/serializer tests when wire data changes |
| Scan/import/history/stats UI logic | Matching `ui/markera/*Test`, `ui/history/HistoryFilterTest`, `ui/stats/*Test`; continuous scan also includes Android `ContinuousScanReplayTest` |
| Android image/cache/sign-in platform code | Matching `androidUnitTest` tests; JPEG/device behavior also needs applicable `androidInstrumentedTest` tests on USB phone `34282ee3` |
| iOS platform code or shared behavior affecting iOS | Relevant tests above plus `:composeApp:iosSimulatorArm64Test` and simulator build via the Mac recipes in `iosApp/README.md` |
| Standalone `server/` | Matching `server/src/test/kotlin/markera/server/*Test` during development; final standalone server suite |
| Build files, dependencies, shared fixtures, unmapped code | Full affected suite; app build configuration changes also require packaging |

Example: targeted development tests may share one invocation:

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest --tests "se.kjellstrand.markera.vision.HitScoringTest" --tests "se.kjellstrand.markera.vision.TargetPlaneTest"
```

## Final review and gate

Keep the independent Tickets test stage. Review and fix first, commit, then run
the full relevant suite once on the final reviewed commit. Development tests do
not replace that gate. Android app changes that need tests and an APK use:

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest :composeApp:assembleDebug
```

Server-only changes use the standalone project, from `server/`:

```powershell
.\gradlew.bat test
```

Mixed changes run both affected gates. Device-specific checks, iOS builds and
release-skill verification remain required where applicable; a JVM gate does
not establish camera, native or release behavior. No store upload is authorized
by a Tickets gate. Keep outputs, configuration cache and daemons during normal
iteration. Reserve `clean`, `--rerun-tasks` or daemon stops for a specific diagnosed
problem or an explicit benchmark, and state why they were needed.

Pure Markdown skips execution only when the Tickets docs-only rule holds: every
changed path ends in `.md` and no test quotes a changed filename/path as a string
(comments/docstrings do not count; search errors prevent a skip). Inspect all test
sources, including Kotlin test source sets, server and eval tests. Independent
review and checklist validation still happen.

## Reuse and invalidation

Record the gate commit and Git tree, command/variant, successful exit and test
summary. Record source cleanliness and relevant ignored/untracked inputs, including
model hash, local Gradle properties (record hashes, never secrets), SDK/JDK/toolchain,
environment/project flags and dependency resolution. For an installable APK also
record its absolute path and SHA-256 (`Get-FileHash -Algorithm SHA256 <apk>`).

A later deploy can reuse that gate only when the workflow permits skipping and
the final source tree, tests and build inputs are demonstrably identical. Compare
the landed tree with the reviewed tree; a merge commit ID alone proves nothing.
Confirm no tracked/untracked build source changed, and compare recorded build inputs
and APK hash. Preserve the evidence outside the source tree (for example in the
card comment). Do not publish secret property contents in evidence.

A later fix, changed integration-base content, test/fixture/configuration change,
changed model/dependency/toolchain/flags, dirty source or missing evidence requires
verification again on the final tree. Run the gate again using normal incremental
Gradle checks; a forced rebuild is not required routinely. If the dispatcher marks
the tree not tested, obey its retest requirement. When exact equivalence cannot
be established, run the appropriate gate before install/push. This guide does not
override dispatcher rules or change its shared implementation.

For APK reuse follow `.claude/skills/deploy/SKILL.md`: the successful gate must
include `assembleDebug`, and the installed artifact must be that recorded APK.
An old file merely existing, a matching timestamp or an unchanged commit without
configuration evidence is insufficient. Preserve phone targeting, install, launch
and foreground checks even when the build is reused.

## Representative comparison

The [#705 baseline](build-performance-baseline.md) measured the existing tasks
on this machine. Separate unchanged test and assemble medians sum to **4.713s**
(1.840 + 2.873), versus **3.052s** for the combined invocation: **1.661s** less
process wall time by this comparison (about 35%). These are independently measured
medians, not a paired end-to-end ticket trial or guaranteed speedup. Separate warm
invocations checked 40 + 55 up-to-date tasks; the shared graph checked 62 once.

The forced combined gate took **64.310s**, while the warm unchanged median was
3.052s. Forced rows used `--rerun-tasks` with cached dependencies and daemons;
they were not clean/cold builds. Warm tests were up-to-date, not fresh test-body
execution. The behavioral resource-edit gate took **37.455s** and executed tests
and packaging; the final restored gate took **16.048s**. These single observations
show why preserving incremental state matters, without treating unchanged checks
as equivalent to an edited-source run. Eliminating a second final gate/install
build is allowed only under the evidence rules above; no additional timing run
is needed to establish the duplicated task graph.
