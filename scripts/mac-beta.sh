#!/bin/bash
# Builds a signed App Store archive and uploads it to TestFlight. Runs fine
# over non-interactive ssh (scripts/mac.sh 'bash scripts/mac-beta.sh'): signing
# uses the dedicated build keychain named in iosApp/fastlane/.env, not the
# login keychain.
set -eo pipefail
export PATH=/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
cd "$(dirname "$0")/../iosApp"
# Release guard (issue #16): never build a release without the hole model (~40 MB, gitignored),
# or every scan in it fails. xcodegen bundles it from the Android assets.
MODEL=../composeApp/src/androidMain/assets/best.onnx
if [ "$(wc -c 2>/dev/null < "$MODEL" || echo 0)" -lt 1000000 ]; then
    echo "Upload refused: $MODEL is missing or too small (copy best.onnx there first)" >&2; exit 1
fi
fastlane beta
