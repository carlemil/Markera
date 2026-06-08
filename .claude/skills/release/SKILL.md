---
name: release
description: Bump minor version and deploy Markera to the Google Play internal test track. Invoke as /release [liveVersion]. Specialized for this repo — :composeApp module, the camera flavor, Swedish release notes.
user_invocable: true
---

# Release: Bump Minor Version and Deploy Markera to Google Play

Specialized for the **Markera** repo:
- Module: **`:composeApp`** (build file `composeApp/build.gradle.kts`).
- App is a **Compose Multiplatform** project; the Android main source set lives under `composeApp/src/androidMain/`.
- Product flavors: **`camera`** (the real-camera shipping app — this is what we release) and **`mock`** (an emulator-only flavor that fakes the camera from bundled dataset images — **never released**).
- `applicationId = "se.kjellstrand.markera"` (the `camera` flavor has no suffix; `mock` adds `.mock`).
- Version lives **inside `defaultConfig`** as `versionCode = <N>` and `versionName = "<X>.<Y>.<Z>"` — not as top-level `val`s.
- Primary locale: **Swedish** (`sv-SE`). No prebuilt database.

**Optional argument:** `<liveVersion>` — the version currently live on Google Play (e.g., `1.2.0`). If provided, release notes are based on changes since git tag `v<liveVersion>`.

Perform the steps in order. Stop and report if any step fails or a prerequisite is missing.

---

## Step 1 — Check prerequisites

### 1a. keystore.properties
Check if `keystore.properties` exists in the project root. If it does **not** exist, stop and tell the user to create it with this format (the `keystore` file is expected at the project root):

```
storeFile=keystore
storePassword=<your-keystore-password>
keyAlias=<your-key-alias>
keyPassword=<your-key-password>
```

Make sure `keystore.properties`, `keystore`/`*.jks`/`*.keystore` are gitignored.

### 1b. play-account.json
Check if `play-account.json` exists in the project root. If it does **not** exist, stop and tell the user to:
1. Go to Google Play Console → Setup → API access
2. Link a Google Cloud project and create a service account with "Release manager" role
3. Download the JSON key and save it as `play-account.json` in the project root
4. Add `play-account.json` to `.gitignore`

### 1c. Gradle Play Publisher plugin
Read `composeApp/build.gradle.kts` and check if `com.github.triplet.play` is in the `plugins {}` block.

If it is **not** present, add it now:
- In the `plugins {}` block (after the existing `alias(...)` lines), add:
  ```kotlin
  id("com.github.triplet.play") version "4.0.0"
  ```
  (4.0.0 is the first release that supports AGP 9.x; 3.x is AGP 8-only.)
- After the closing `}` of the `android {}` block (before any top-level task/`dependencies {}` declarations), add:
  ```kotlin
  play {
      serviceAccountCredentials.set(rootProject.file("play-account.json"))
      track.set("internal")
      defaultToAppBundles.set(true)
  }
  ```

### 1d. Signing config
Read `composeApp/build.gradle.kts` and check if a `signingConfigs` block exists inside `android {}`.

If it is **not** present, add the following:

Before the `android {` block (top level, near the other top-level `val`s), add:
```kotlin
val keystoreProperties = java.util.Properties().also { props ->
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
}
```

Inside the `android {}` block, **before `defaultConfig`**, add:
```kotlin
    signingConfigs {
        create("release") {
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile", "keystore"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }
```

Inside `buildTypes { release { ... } }`, add:
```kotlin
            signingConfig = signingConfigs.getByName("release")
```

---

## Step 2 — Bump the version

Read `composeApp/build.gradle.kts` and find, inside `defaultConfig`:
```
versionCode = <N>
versionName = "<X>.<Y>.<Z>"
```

Compute new values:
- `newVersionCode` = N + 1
- New version name:
  - **If the current name has a `-candidate-NNN` suffix** (e.g. `"1.2.0-candidate-001"`): strip the suffix and use the base version as-is (e.g. `"1.2.0"`).
  - **Otherwise**: parse `X.Y.Z` and set new version = `"<X>.<Y+1>.0"`. (Normalize a two-part name like `"1.0"` to `1.0.0` first, so the bump yields `"1.1.0"`.)

Update the file:
- Replace `versionCode = <N>` with `versionCode = <newVersionCode>`
- Replace `versionName = "<current>"` with `versionName = "<newVersionName>"`

Show the user the old and new version strings before continuing.

---

## Step 3 — Generate release notes

### 3a. Determine the last released version

**If `<liveVersion>` was provided:** use `v<liveVersion>` as the baseline tag. Verify it exists with `git tag -l v<liveVersion>`. If it doesn't, warn and ask whether to create it on a specific commit or fall back.

**Otherwise, use git tags.** Releases are tagged `v<version>`. Find the most recent:
```
git describe --tags --abbrev=0 --match "v*" 2>/dev/null
```

**Verification (when no argument was given): cross-check with Google Play.** Fetch the listing and extract the live version:
```
curl -s "https://play.google.com/store/apps/details?id=se.kjellstrand.markera&hl=en-US" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | sort -u
```
If the Play version differs from the latest git tag, tell the user and ask which to use as the baseline. (Note: the app may not be published yet — if the listing 404s, just use git tags.)

**Fallback:** if no tags exist, find the previous "Bump version" commit:
```
git log --grep="^Bump version" --format="%H" -1
```

### 3b. Get commits since the last release
```
git log --oneline v<lastVersion>..HEAD
```
If using the fallback commit hash:
```
git log --oneline <commitHash>..HEAD
```
If nothing works, use the last 20 commits:
```
git log --oneline -20
```

### 3c. Write the release notes file
Write Swedish release notes to:
```
composeApp/src/androidMain/play/release-notes/sv-SE/default.txt
```
Create parent directories if needed. (If Step 5's publish later reports it found no release notes, the Play plugin may resolve the main source set differently — fall back to `composeApp/src/main/play/release-notes/sv-SE/default.txt` or the flavor dir `composeApp/src/camera/play/release-notes/sv-SE/default.txt`.)

Rules:
- **Write in Swedish.**
- **Maximum 500 characters** (Google Play hard limit — count carefully).
- Focus on what the **user experiences**, not implementation detail.
- Short, friendly language — no jargon, commit hashes, or branch names.
- Bullet list with short lines if there are multiple changes.
- If nothing is user-visible, write a generic "Buggfixar och förbättringar" line.
- Show the generated text to the user before continuing.

---

## Step 4 — Build the signed AAB

Build the **camera** flavor (the shipping app — never `mock`):
```
./gradlew :composeApp:bundleCameraRelease --no-daemon
```

Wait for it to complete. If it fails, show the error output and stop.

The built AAB will be at:
```
composeApp/build/outputs/bundle/cameraRelease/composeApp-camera-release.aab
```

---

## Step 5 — Upload to Google Play internal test track

```
./gradlew :composeApp:publishCameraReleaseBundle --no-daemon
```

Wait for it to complete. If it fails, show the error output and stop.

---

## Step 6 — Commit and tag the release

```
git add composeApp/build.gradle.kts
git add composeApp/src/androidMain/play/release-notes/
git commit -m "Bump version to <newVersionName> (build <newVersionCode>)"
git tag v<newVersionName>
```

Adjust the `git add` path for release notes if you used a fallback location in Step 3c. End the commit message with the project's standard co-author trailer if one is in use.

---

## Step 7 — Summary

Report:
- Previous version → New version (e.g. `1.0.0 (1) → 1.1.0 (2)`)
- Git tag created (e.g. `v1.1.0`)
- Upload status (success/failure)
- The release notes that were published
- Reminder to promote the build from the internal test track in Google Play Console when ready
- Reminder to push the tag with `git push origin v<newVersionName>`
