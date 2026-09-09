#!/bin/bash
# On the Mac (or via scripts/mac.sh sh scripts/mac-build.sh): generate the Xcode
# project, build the app for the booted simulator, install and launch it.
# CONFIGURATION=Release for a release build. The build log is iosApp/build/xcodebuild.log.
set -eo pipefail
export PATH=/opt/homebrew/bin:$PATH
cd "$(dirname "$0")/../iosApp"
SIM='iPhone 17 Pro Max'   # more than one simulator may be booted, so never say "booted"
[ -f Config/Local.xcconfig ] || cp Config/Local.xcconfig.example Config/Local.xcconfig
xcodegen generate -q
mkdir -p build
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration "${CONFIGURATION:-Debug}" \
    -sdk iphonesimulator -destination "platform=iOS Simulator,name=$SIM" \
    -derivedDataPath build/dd build > build/xcodebuild.log 2>&1 || { grep -E 'error:|BUILD' build/xcodebuild.log | tail -20; exit 1; }
tail -1 build/xcodebuild.log
xcrun simctl boot "$SIM" 2>/dev/null || true
xcrun simctl install "$SIM" "build/dd/Build/Products/${CONFIGURATION:-Debug}-iphonesimulator/iosApp.app"
xcrun simctl launch "$SIM" se.kjellstrand.markera
