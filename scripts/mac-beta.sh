#!/bin/bash
# Builds a signed App Store archive and uploads it to TestFlight. Runs fine
# over non-interactive ssh (scripts/mac.sh 'bash scripts/mac-beta.sh'): signing
# uses the dedicated build keychain named in iosApp/fastlane/.env, not the
# login keychain.
set -eo pipefail
# UI-test gate (issue #15): refuse unless AppUiTest passed on the phone for exactly this commit
# (the record comes over with scripts/mac-sync.sh). SKIP_UI_GATE=1 overrides, loudly.
sh "$(dirname "$0")/ui-gate.sh"
export PATH=/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
cd "$(dirname "$0")/../iosApp"
fastlane beta
