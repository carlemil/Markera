---
name: deploy
description: Build the app and install + launch it on the USB-connected phone. Invoke as /deploy. Project-specific — :composeApp debug APK via adb; includes USB-mode and lockscreen troubleshooting.
user_invocable: true
---

# Deploy: Build, Install and Launch on the Phone

Builds the debug APK, installs it on the physically connected Android phone over
adb, launches it, and verifies it reached the foreground. The app needs a back
camera.

Perform these steps in order. The build/evidence check (Step 1) and device check
(Step 2) are independent and may run in parallel.

---

## Step 1 — Build

First check whether the current workflow already produced a verified debug APK.
Reuse it only when the successful final gate included `assembleDebug`, the exact
reviewed source tree and all configuration/build inputs still match, the workflow
permits reuse, and its recorded APK SHA-256 matches the file to install. Follow
`docs/ticket-verification.md` for evidence and invalidation rules. An existing APK
or a matching commit alone does not qualify. Missing evidence or changed inputs
requires the appropriate final gate again; never skip the initial build blindly.

When tests and packaging are both required, run once from the project root:

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest :composeApp:assembleDebug
```

For a standalone deploy requiring only packaging, build from the project root:

```
.\gradlew.bat :composeApp:assembleDebug
```

Do not routinely clean, force task reruns or stop daemons. Record the successful
command, source/configuration evidence and APK hash before reuse. Reuse removes
only duplicate Gradle work: all device/install/foreground checks below still run.

If the build fails with "model not found"-style asset errors, the ONNX model is
missing at `composeApp/src/androidMain/assets/best.onnx` (~40 MB, not in git);
tell the user and stop.

The APK lands at:

```
composeApp\build\outputs\apk\debug\composeApp-debug.apk
```

---

## Step 2 — Find the phone

```
adb devices
```

Expect a serial with state `device`. Troubleshooting, in order:

1. **Empty list** — restart the server (`adb kill-server; adb devices`) and
   check whether Windows sees the phone at all:
   ```powershell
   Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -match '^USB\\VID_18D1' } | Select-Object Status, Class, FriendlyName, InstanceId
   ```
   `VID_18D1` is Google. If a composite device shows up but adb stays empty,
   the phone is enumerating **without an ADB interface** (e.g. PID `4EE8` =
   MIDI-only — this has happened on this phone). Ask the user to toggle
   **Settings → Developer options → USB debugging** off/on and accept the
   "Allow USB debugging?" dialog, then re-check.
2. **`unauthorized`** — the user must accept the RSA-fingerprint dialog on the
   phone.
3. Still nothing — the user must check cable/port; stop and report.

Use the USB OnePlus 9 Pro, serial `34282ee3`; do not start an emulator. Use
`adb -s 34282ee3 ...` for every later command in case more devices appear.

---

## Step 3 — Install

```
adb -s 34282ee3 install -r "<absolute path to the built or verified debug APK>"
```

Use the actual build checkout's APK path (including a card worktree when applicable),
not an unrelated APK in the main checkout. Recheck its recorded hash before install
when reusing an artifact.

Expect `Success`. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means a Play-signed or
differently-signed build is on the phone — ask the user before uninstalling
(`adb uninstall se.kjellstrand.markera` loses app data).

---

## Step 4 — Launch and verify

```
adb -s <serial> shell monkey -p se.kjellstrand.markera -c android.intent.category.LAUNCHER 1
```

Then verify it is actually running in the foreground:

```
adb -s <serial> shell "pidof se.kjellstrand.markera; dumpsys activity activities | grep -m1 topResumedActivity"
```

Success = a pid is printed and `topResumedActivity` names
`se.kjellstrand.markera/.MainActivity`. A `Broken pipe` line after the grep is
harmless.

If the screen is off or locked the app still launches, but nothing is visible
and screenshots come back black/empty (secure keyguard surfaces can't be
captured). `input keyevent KEYCODE_WAKEUP` + `wm dismiss-keyguard` wakes an
unsecured phone; a PIN/fingerprint lock needs the user — ask them to unlock.

---

## Step 5 — Report

State the device serial, and the verification result
(pid + top activity). If the user's request implies exercising a change (not
just deploying), the detect flow can be driven and observed with:

- Tap the detect FAB (bottom-right): `adb -s <serial> shell input tap 1264 2880`
  (valid for this phone's 1440×3216 @ 640 dpi screen).
- Watch results: `adb -s <serial> logcat -d -s Markera HoleDetector System.out` — the app
  (`TargetScanner`'s `println`s land under `System.out`)
  logs snapshot size, raw/kept detection counts, centre method, and
  `inference took N ms`.
