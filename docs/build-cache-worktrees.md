# Local build cache across worktrees

`org.gradle.caching=true` enables Gradle's existing per-user **local** task-output
cache. Keep the same Gradle user home across Markera ticket worktrees; do not copy
`build/` or `.gradle/` between them. Configuration-cache entries and incremental
compiler state remain checkout-local. No remote cache is configured.

## Experiment (2026-10-18)

Both isolated worktrees started at `b8a5b93`, on the Windows machine described in
[the baseline](build-performance-baseline.md). Gradle 9.4.1, Java 21 daemon,
configuration cache enabled. Inspection found no user-home `gradle.properties`
override or init scripts; repository task-output caching was off. Dependency
caches and daemons were retained. Other ticket agents yielded the Windows build
slot during these measurements.

To avoid pre-existing task-cache hits, a temporary init script redirected only
the local build cache into a new empty directory; **Gradle user home was not
changed**. Each checkout had SDK-only `local.properties` and the same ignored
model; private service-account, Sentry upload and signing files were absent.
The default public mobile OAuth/BuildConfig settings were retained. No scan or
artifact was uploaded.

All rows invoked `:composeApp:testDebugUnitTest :composeApp:assembleDebug`
with `--console=plain --no-scan --info`. Wall seconds include process startup.
`clean` was a separate untimed invocation where specified. These are ordered
single observations, not randomized medians or cold dependency-download builds.

| State | Seconds | FROM-CACHE | UP-TO-DATE |
|---|---:|---:|---:|
| First checkout, no local outputs, `--no-build-cache` | 46.638 | 0 | 4 |
| Same checkout after `clean`, empty local cache, `--build-cache` | 29.900 | 0 | 5 |
| Second checkout, no local outputs, shared populated cache | 16.756 | 32 | 4 |
| Second checkout after `clean`, populated cache | 7.939 | 32 | 5 |

The first reuse saved 29.882s against the first cache-off observation, but that
is **not** an isolated causal speedup estimate: daemon/JIT/filesystem/configuration
state warmed over the ordered runs. The direct evidence is that 32 tasks restored
outputs across different absolute checkout paths, including app and test Kotlin
compilation and the test task. Restored test results represent identical declared
inputs; they do not execute test bodies again. A forced independent gate remains
available when fresh execution is required.

## Invalidation

The second checkout retained its outputs while editing one file at a time; exact
original bytes were restored in `finally` blocks. The Kotlin spelling-only edit
`MANUAL_HOLE_FALLBACK_PX = 20f` to `20.0f` reran Kotlin compilation; unchanged
bytecode kept tests/package up-to-date. Restoration retrieved the original compile
output from cache. This checks source fingerprint invalidation, not behavioral
change coverage. The Compose string edit `Detect` to `Detect target` checks
resource processing and downstream rebuilds.

| Edit/restoration gate | Seconds | FROM-CACHE | UP-TO-DATE |
|---|---:|---:|---:|
| Kotlin equivalent spelling | 20.881 | 0 | 61 |
| Kotlin original restored | 3.295 | 1 | 61 |
| Compose string changed | 38.439 | 0 | 45 |
| Compose string original restored | 11.136 | 7 | 45 |

Resource change executed resource generation, app compilation, tests, asset
compression and APK packaging; restoration reused seven cached outputs. Every
matrix invocation exited zero, and configuration-cache reuse was reported for
all four edit/restoration rows. Both tracked files finished byte-identical to
`b8a5b93`.

## Cache contents and limits

Inspection of the initial 31 gzip/tar cache entries (105,758,170 bytes) found the
model in Sentry's generated asset output and compressed Android assets. Compiled
BuildConfig contains the public values intentionally shipped in the APK. No
keystore, signing-key, `local.properties`, `sentry.properties`, `.jks` or `.p12`
paths appeared in these entries; none of the private files was supplied to the
experiment. Android signing validation and APK packaging were non-cacheable.
This is a bounded debug-gate audit, not a claim about every future plugin/release
task. Do not mark credential-consuming tasks cacheable or add secrets to generated
sources. Keep the cache private to this user/machine; never publish it remotely.

`--info` showed several tasks intentionally non-cacheable: Android asset merging,
native-library merging/stripping, APK packaging, Compose resource generation,
and runtime/compile jar bundling. Their remaining work explains why a fresh
checkout is slower than an unchanged same-checkout gate. Do not override plugin
cacheability just to turn these into hits. Configuration-cache reuse survives the
source/resource edits, but a new checkout must create its own configuration entry;
local property, build-script and init-script changes may invalidate it.

## Reproduce

Use two disposable checkouts at the same commit and copy only the documented
ignored SDK/model prerequisites. Preserve the normal Gradle user home. In a
scratch `cache.init.gradle`, set:

```groovy
settingsEvaluated { settings ->
    settings.buildCache {
        local { directory = new File('C:/your-private-scratch/empty-cache') }
    }
}
```

Run the combined gate first with `--no-build-cache`, then after `clean` with
`--build-cache -I C:/your-private-scratch/cache.init.gradle`. Run that same cached
command in the fresh second checkout. Use `--info` to inspect task cache keys and
reasons; retain logs locally. Never delete the normal user's dependency/build
cache for a benchmark. Normal development needs neither the init script nor
`clean`: the project property uses Gradle's default local cache location.

Disable task-output cache for one invocation with `--no-build-cache`; use
`--rerun-tasks` for the full forced verification gate. No remote cache was tested
or justified by this local experiment.

Reference: [Gradle build cache](https://docs.gradle.org/current/userguide/build_cache.html).
