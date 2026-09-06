# iOS app (placeholder)

This directory is reserved for the iOS Xcode project that consumes the shared
Compose Multiplatform framework produced by the `:composeApp` module
(`baseName = "ComposeApp"`).

## Scaffold the Xcode project

Option A — Kotlin Multiplatform Wizard (https://kmp.jetbrains.com/):
choose "Compose Multiplatform UI" with iOS enabled, then copy the generated
`iosApp/` contents on top of this directory.

Option B — Android Studio with the Kotlin Multiplatform plugin: right-click
the project root and pick **New > iOS App in iosApp/**.

## Wire the framework into Xcode

In the iOS app target's **Build Phases**, add a *Run Script* phase that runs:

```sh
cd "$SRCROOT/.."
./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
```

Add `$(SRCROOT)/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` to the
target's **Framework Search Paths**, and link `ComposeApp.framework`.

## Entry point

In `iOSApp.swift` (or the `UIApplicationDelegate`), present the root view:

```swift
import UIKit
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController =
        MainViewControllerKt.MainViewController()
    func updateUIViewController(_ vc: UIViewController, context: Context) {}
}
```

`MainViewController()` lives at
`composeApp/src/iosMain/kotlin/se/kjellstrand/markera/MainViewController.kt`.

## Build the iOS framework standalone

```sh
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

## Backend + Sign in with Apple

Entry points live in `composeApp/src/iosMain/.../series/`: `SeriesServices`
(HTTP client → `SeriesApi` → `BackendSessionRepository`, token in
`NSUserDefaults`) and `signInWithProvider(session)` (AuthenticationServices,
call it from the main thread).

Xcode target setup:

- Enable the **Sign in with Apple** capability (entitlement
  `com.apple.developer.applesignin`).
- The target's bundle id must equal `APPLE_BUNDLE_ID` in the server's `.env` —
  the backend verifies it as the identity token's audience.
- The backend is plain HTTP on the LAN, so add an ATS exception in `Info.plist`:
  `NSAppTransportSecurity` → `NSExceptionDomains` → `192.168.1.191` →
  `NSExceptionAllowsInsecureHTTPLoads = YES`.
