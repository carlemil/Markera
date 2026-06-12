---
name: deploy
description: Build the camera flavor and install + launch it on the USB-connected phone. Invoke as /deploy [mock]. Project-specific — :composeApp cameraDebug via adb; includes USB-mode and lockscreen troubleshooting.
user_invocable: true
---

# Deploy: Build, Install and Launch on the Phone

Builds the debug APK, installs it on the physically connected Android phone over
adb, launches it, and verifies it reached the foreground. The default is the
`camera` flavor (the real app, needs a back camera). Pass `mock` as the argument
to deploy the emulator/dev flavor instead (applicationId suffix `.mock`).

Perform these steps in order. The build (Step 1) and the device check (Step 2)
are independent — run them in parallel; the build takes the longest.

---

## Step 1 — Build

From the project root (background it; it takes ~30 s warm, a few minutes cold):

```
.\gradlew.bat :composeApp:assembleCameraDebug
```

For the `mock` argument use `:composeApp:assembleMockDebug` instead, and
substitute `mock` for `camera` in every path/id below.

If the build fails with "model not found"-style asset errors, the ONNX model is
missing at `composeApp/src/androidMain/assets/best.onnx` (~40 MB, not in git);
tell the user and stop.

The APK lands at:

```
composeApp\build\outputs\apk\camera\debug\composeApp-camera-debug.apk
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

Use `adb -s <serial> ...` for every later command in case more devices appear.

---

## Step 3 — Install

```
adb -s <serial> install -r "D:\source\Markera\composeApp\build\outputs\apk\camera\debug\composeApp-camera-debug.apk"
```

Expect `Success`. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means a Play-signed or
differently-signed build is on the phone — ask the user before uninstalling
(`adb uninstall se.kjellstrand.markera` loses app data).

---

## Step 4 — Launch and verify

```
adb -s <serial> shell monkey -p se.kjellstrand.markera -c android.intent.category.LAUNCHER 1
```

(For the mock flavor the package is `se.kjellstrand.markera.mock`.)

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

State the variant deployed, the device serial, and the verification result
(pid + top activity). If the user's request implies exercising a change (not
just deploying), the detect flow can be driven and observed with:

- Tap the detect FAB (bottom-right): `adb -s <serial> shell input tap 1264 2880`
  (valid for this phone's 1440×3216 @ 640 dpi screen).
- Watch results: `adb -s <serial> logcat -d -s Markera HoleDetector` — the app
  logs snapshot size, raw/kept detection counts, centre method, and
  `inference took N ms`.
