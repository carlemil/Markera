# Integrated vision module (#723)

Measured 20 October 2026 on Windows, starting at master `4695a89` in an isolated
worktree. This implements the boundary proposed by the [#709 prototype](shared-logic-test-benchmark.md).
Ten production files and six existing suites moved byte-for-byte into `:vision`;
packages, public scoring behavior and internal helper visibility are unchanged.
No series, SQLDelight, UI or platform adapter moved. Centre remains exclusively
the digit 6–9 row-line intersection; ellipse geometry supplies scale/perspective.

`:vision` targets Android, JVM, iosX64, iosArm64 and iosSimulatorArm64. App and
eval consume the library rather than including another source directory. The app
exports it through its static iOS framework. Eval now uses Java 21 to consume the
same JVM artifact. Pinned Kotlin 2.2.10 / AGP 9.2.1 remain unchanged; the Android
library uses the repository's existing transitional AGP compatibility flags.

## Controlled current-source observations

Seconds are wrapper process wall time. Both phases use Java 21 toolchains/daemon
criteria, Gradle 9.4.1, `-Xmx4g -Dfile.encoding=UTF-8`, and explicit
`--build-cache --configuration-cache --console=plain`. Launcher Oracle 17.0.1 is
distinct from selected Java 21 daemon/compiler. Local config/model SHA256s were
recorded privately; no secret values are published. Windows build slot reserved
exclusively; ordinary system activity was not disabled.

| Scope | Observation | Before | Integrated |
|---|---|---:|---:|
| Target (20 tests) | warm | 35.482 | 7.577 |
| Target | unchanged | 3.040 | 3.548 |
| Six suites (69 tests) | warm | 3.853 | 7.165 |
| Six suites | unchanged | 2.178 | 3.239 |
| Combined (383 unique tests + APK) | warm | 16.859 | 8.889 |
| Combined | unchanged | 2.314 | 3.795 |
| Target | edit 1 | 45.965 | 11.817 |
| Target | edit 2 | 18.278 | 4.687 |
| Target | edit 3 | 19.525 | 4.407 |
| Six suites | edit 1 | 23.281 | 6.021 |
| Six suites | edit 2 | 19.007 | 5.504 |
| Six suites | edit 3 | 18.998 | 5.057 |
| Combined | edit | 24.585 | 13.084 |
| Combined | restored | 5.335 | 5.651 |

Target edit medians: **19.525 → 4.687 s**, saving **14.838 s (76.0%)**.
Six-suite edit medians: **19.007 → 5.504 s**, saving **13.503 s (71.0%)**.
Combined edit is only one observation: **24.585 → 13.084 s**. The integrated
unchanged combined graph was **1.481 s slower**; restoring original source was
**0.316 s slower**. Extraction improves these measured pure-source edit cycles,
but adds a module's configuration/tasks. This does not establish a whole-app,
cold-CI, native-build or total-ticket speedup. The noisy first edit remains in
the report; three observations are descriptive, not a confidence interval.

Each scope/trial appended the identical unused internal function body to
`HitScoring.kt` before/after extraction, with unique names
`benchmark72320261020{target|full}{1|2|3}` returning the trial number. The combined
edit used `benchmark72320261020combined(): Int = 723`. Tests and production compile
tasks executed on every edit row, with zero failures. Warm/unchanged/restored
rows can be UP-TO-DATE/FROM-CACHE and are not fresh-execution speed evidence.
No clean, forced rerun or daemon stop was used. Warm setup is not a cold build;
the initial new-module integration gate separately took 3m14s (88 tasks executed).

The before matrix necessarily preceded extraction and the after matrix followed
integration; these are matched observations, not an interleaved simultaneous
Android/JVM experiment. Within each phase, scope order alternated target/full,
full/target, target/full. Original bytes were restored after each edit and in a
guarded finally block refusing to overwrite concurrent changes. Both restoration
checks returned SHA256
`AA38C180175A6C47D5DE17C8AACE64481C843D8184242D36291AEF26560B8661`.

Before target/six-suite commands selected existing classes on
`:composeApp:testDebugUnitTest`; after commands select the same classes on
`:vision:jvmTest`. Combined before runs app tests + assembleDebug. Combined after
adds `:vision:testDebugUnitTest` to app tests + assembleDebug, preserving the same
383 unique Android tests: **69 moved + 314 remaining**. JVM and Android library
runs each cover the same 69 assertions, not 138 unique tests. The remaining app
adapter/integration suites still compile against the real library.

Full raw task outcomes, source/test Git blob inventory, copied-input hashes,
guarded measurement script, JSON observations and restoration receipts are kept
outside source control in the card's development evidence directory. Reproduction
uses the same commands/edits above, a fresh function-name nonce, and exclusive
build scheduling. Preserve all observations including slower rows.

## Verification boundary

Use the [verification guide](ticket-verification.md) for final gates. Development
integration passed both library targets, all 314 remaining app tests, APK packaging
and eval's real inference test. A fixed-seed fresh mosaic and native/framework/Xcode
checks validate dependency wiring separately; independent review and the real
USB-phone scoring/photo check remain required before landing. Pure JVM timings
cannot establish platform correctness.
