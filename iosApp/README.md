# iOS app

The Xcode project is generated from `project.yml` by [xcodegen](https://github.com/yonaskolb/XcodeGen);
`iosApp.xcodeproj` is gitignored. The app links the static `ComposeApp`
framework from `:composeApp` (built by the pre-build script through
`embedAndSignAppleFrameworkForXcode`), the ONNX Runtime Objective-C bindings
(Swift package, pinned by commit in `project.yml`) and the system sqlite.
`best.onnx` is bundled from `composeApp/src/androidMain/assets/` (gitignored, so copy
it into the Mac clone too — `~/eval/best.onnx` on the Mac mini); it must have fp32
inputs/outputs (`scripts/cast_model_io.py`), the ORT Objective-C API has no fp16
tensors.

## Build and run on the simulator (Mac mini)

From Windows, mirror the working tree and build over ssh:

```sh
sh scripts/mac-sync.sh                     # HEAD + uncommitted files -> ~/source/Markera on the Mac
sh scripts/mac.sh sh scripts/mac-build.sh  # xcodegen + xcodebuild + simctl install/launch
```

`scripts/mac-build.sh` copies `Config/Local.xcconfig.example` to the gitignored
`Config/Local.xcconfig` (developer team) on first use, writes the build log to
`iosApp/build/xcodebuild.log` and launches `se.kjellstrand.markera` on the booted
"iPhone 17 Pro Max". `CONFIGURATION=Release` builds the release configuration; its
optimised framework link runs inside the Gradle daemon and needs the 4 GB
`org.gradle.jvmargs` in `gradle.properties` (`sh gradlew --stop` after changing it).

Framework-only checks without Xcode (faster, from Windows):

```sh
sh scripts/mac.sh 'sh gradlew -q :composeApp:linkDebugFrameworkIosSimulatorArm64'
sh scripts/mac.sh 'sh gradlew -q :composeApp:linkDebugFrameworkIosArm64'      # device
sh scripts/mac.sh 'sh gradlew :composeApp:iosSimulatorArm64Test'             # commonTest on K/N
```

Kotlin/Native rejects backtick test names containing `,` or `()`.

Useful on the Mac (`export PATH=/opt/homebrew/bin:$PATH` first):

```sh
xcrun simctl io booted screenshot shot.png
xcrun simctl addmedia booted ~/eval/IMG20260426151712.jpg   # a target photo into Photos
xcrun simctl spawn booted log stream --predicate 'process == "iosApp"'
```

The app's own `println`s (`Markera:`, `HoleDetector:`, `DigitDetector:`) are easiest
to read by launching with a console:

```sh
xcrun simctl terminate $U se.kjellstrand.markera
nohup xcrun simctl launch --console-pty $U se.kjellstrand.markera > ~/eval/app.log 2>&1 &
grep -E 'Markera:|HoleDetector|DigitDetector|Uncaught' ~/eval/app.log
```

Taps and the accessibility tree come from [idb](https://fbidb.io) (`brew install
facebook/fb/idb-companion`, `pipx install fb-idb`, client in `~/.local/bin`);
Compose fills the tree after the first query, so tap once before describing:

```sh
U=$(xcrun simctl list devices | grep 'iPhone 17 Pro Max' | grep -o '[0-9A-F-]\{36\}')
idb ui tap --udid $U 220 490
idb ui describe-all --udid $U --nested   # labels + frames in points (440x956)
idb ui text --udid $U 'hello'
```

## Configuration

`Debug` points the app at `http://127.0.0.1:8091` with dev auth (Info.plist
`MarkeraBackendUrl` / `MarkeraDevAuth`); on the simulator, Sign in with Apple fails
(error 1000) and the app falls back to `POST /auth/dev` as "ios-sim". Run a dev
backend on the Mac from the image the production compose build already produced
(the server's Gradle build wants a JDK 17 toolchain the Mac lacks):

```sh
docker run -d --name markera-dev -p 127.0.0.1:8091:8080 -e DEV_AUTH=true \
  -e APPLE_BUNDLE_ID=se.kjellstrand.markera -e DB_PATH=/data/markera.db \
  -e TZ=Europe/Stockholm -v markera-dev-data:/data server-markera-server:latest
curl -s http://127.0.0.1:8091/health   # {"status":"ok"}; `docker start markera-dev` later
```

Production stays in `server-markera-server-1` on 8090.
`Release` uses `https://markera.duckdns.org` without dev auth. Sign in with Apple
needs the App ID `se.kjellstrand.markera` with that capability and
`APPLE_BUNDLE_ID=se.kjellstrand.markera` in the server's `.env` (the identity
token's audience).

## Entry point

`iosApp/iOSApp.swift` presents `MainViewController(holeModel:)` from
`composeApp/src/iosMain/kotlin/se/kjellstrand/markera/MainViewController.kt`, passing
`OrtHoleModel` (`HoleModel.swift`, an `ORTSession` over the bundled model) — the
Kotlin `HoleDetector` letterboxes the image and parses the `[1,300,6]` output, Swift
only runs the tensor. Digits come from Apple Vision (`DigitDetector.ios.kt`); the
frame from AVFoundation on a device or `PHPickerViewController` where there is no
camera (`FrameSource.ios.kt`). The device camera path is compile-only so far (no
iPhone to test on).

## TestFlight

`iosApp/fastlane/` has a `beta` lane (xcodegen → signed App Store archive → TestFlight
with `metadata/sv/release_notes.txt` as the changelog). It needs
`iosApp/fastlane/.env` (copy `.env.template`; the App Store Connect key `.p8` and
`.env` are gitignored) and the app record on App Store Connect:

```sh
sh scripts/mac.sh 'bash scripts/mac-beta.sh'
```

Signing uses a dedicated build keychain (`BUILD_KEYCHAIN_PATH`/`_PASSWORD` in
`.env`; creation recipe in `.env.template`) because codesign cannot use the
locked login keychain over non-interactive ssh — the lane unlocks it, and `cert`
creates/imports the Apple Distribution identity there. The script
sets a UTF-8 locale (without it fastlane dies with `"Cr" on UTF-16` inside
`build_app`) and runs `fastlane beta`. The lane enables Sign in with Apple on the
App ID if missing and signs manually at archive time only (`Local.xcconfig`
stays automatic for simulator builds). The deployment target must stay equal to
the ORT xcframework's `MinimumOSVersion` (see `project.yml`).

## App Store listing and review

`fastlane metadata` pushes the listing to the editable App Store version without a
binary: `fastlane/metadata/` (Swedish texts, `primary_category.txt`, `copyright.txt`,
`review_information/`, `app_rating.json` = the full age-rating questionnaire, every
attribute the API requires), `fastlane/screenshots/sv/` (iPhone 6.9" = 1320×2868 from
the iPhone 17 Pro Max simulator; the device is inferred from the size, `overwrite_screenshots`
replaces the set). The version string follows `MARKETING_VERSION` in `project.yml`.
`fastlane release` does the same, attaches the newest processed TestFlight build and
submits for review (export compliance: no non-exempt encryption). Both run fine over
`scripts/mac.sh` with the API key alone, but need a UTF-8 locale:

```sh
sh scripts/mac.sh 'cd iosApp && export LC_ALL=en_US.UTF-8 && fastlane metadata'
sh scripts/mac.sh 'cd iosApp && export LC_ALL=en_US.UTF-8 && fastlane release'
```

Not reachable through the API and done once in the App Store Connect UI: the App
Privacy questionnaire (account name/user id, photos, user content; all linked to the
user, none used for tracking).
