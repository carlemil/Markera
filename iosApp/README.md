# iOS app

The Xcode project is generated from `project.yml` by [xcodegen](https://github.com/yonaskolb/XcodeGen);
`iosApp.xcodeproj` is gitignored. The app links the static `ComposeApp`
framework from `:composeApp` (built by the pre-build script through
`embedAndSignAppleFrameworkForXcode`), the ONNX Runtime Objective-C bindings
(Swift package, pinned by commit in `project.yml`) and the system sqlite.

## Build and run on the simulator (Mac mini)

From Windows, mirror the working tree and build over ssh:

```sh
sh scripts/mac-sync.sh                     # HEAD + uncommitted files -> ~/source/Markera on the Mac
sh scripts/mac.sh sh scripts/mac-build.sh  # xcodegen + xcodebuild + simctl install/launch
```

`scripts/mac-build.sh` copies `Config/Local.xcconfig.example` to the gitignored
`Config/Local.xcconfig` (developer team) on first use, writes the build log to
`iosApp/build/xcodebuild.log` and launches `se.kjellstrand.markera` on the booted
"iPhone 17 Pro Max". `CONFIGURATION=Release` builds the release configuration.

Useful on the Mac (`export PATH=/opt/homebrew/bin:$PATH` first):

```sh
xcrun simctl io booted screenshot shot.png
xcrun simctl addmedia booted ~/eval/IMG20260426151712.jpg   # a target photo into Photos
xcrun simctl spawn booted log stream --predicate 'process == "iosApp"'
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
`MarkeraBackendUrl` / `MarkeraDevAuth`); run the backend locally in `server/`:

```sh
PORT=8091 DEV_AUTH=true APPLE_BUNDLE_ID=se.kjellstrand.markera DB_PATH=/tmp/markera-dev.db sh gradlew run
```

`Release` uses `https://markera.duckdns.org` without dev auth. Sign in with Apple
needs the App ID `se.kjellstrand.markera` with that capability and
`APPLE_BUNDLE_ID=se.kjellstrand.markera` in the server's `.env` (the identity
token's audience).

## Entry point

`iosApp/iOSApp.swift` presents `MainViewControllerKt.MainViewController()` from
`composeApp/src/iosMain/kotlin/se/kjellstrand/markera/MainViewController.kt`.
