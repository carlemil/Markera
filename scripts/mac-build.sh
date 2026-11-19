#!/bin/bash
# On the Mac (or via scripts/mac.sh sh scripts/mac-build.sh): generate the Xcode
# project, build the app for the booted simulator, install and launch it.
# CONFIGURATION=Release for a release build. The build log is iosApp/build/xcodebuild.log.
set -eo pipefail
export PATH=/opt/homebrew/bin:$PATH
cd "$(dirname "$0")/../iosApp"
SIM='iPhone 17 Pro Max'
# The simulator by UDID, on the newest general iOS runtime (not a device-specific one such as
# iOS-27-2-iPhone19-4), created there if missing. A bare name means "OS:latest" to Xcode 27, and
# the same name may exist on older runtimes too; more than one may be booted, so never "booted".
RUNTIME=$(xcrun simctl list runtimes available | awk '$NF ~ /SimRuntime\.iOS-[0-9-]+$/ { r = $NF } END { print r }')
SIM_ID=$(xcrun simctl list devices available -j | /usr/bin/python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"].get(sys.argv[1], [])
print(next((d["udid"] for d in devices if d["name"] == sys.argv[2]), ""))' "$RUNTIME" "$SIM")
[ -n "$SIM_ID" ] || SIM_ID=$(xcrun simctl create "$SIM" "$SIM" "$RUNTIME")
[ -f Config/Local.xcconfig ] || cp Config/Local.xcconfig.example Config/Local.xcconfig
xcodegen generate -q
mkdir -p build
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration "${CONFIGURATION:-Debug}" \
    -sdk iphonesimulator -destination "platform=iOS Simulator,id=$SIM_ID" \
    -derivedDataPath build/dd build > build/xcodebuild.log 2>&1 || { grep -E 'error:|BUILD' build/xcodebuild.log | tail -20; exit 1; }
tail -1 build/xcodebuild.log
xcrun simctl boot "$SIM_ID" 2>/dev/null || true
xcrun simctl install "$SIM_ID" "build/dd/Build/Products/${CONFIGURATION:-Debug}-iphonesimulator/iosApp.app"
xcrun simctl launch "$SIM_ID" se.kjellstrand.markera
