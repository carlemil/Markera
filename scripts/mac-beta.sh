#!/bin/bash
# Builds a signed App Store archive and uploads it to TestFlight. Runs fine
# over non-interactive ssh (scripts/mac.sh 'bash scripts/mac-beta.sh'): signing
# uses the dedicated build keychain named in iosApp/fastlane/.env, not the
# login keychain.
set -eo pipefail
export PATH=/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
cd "$(dirname "$0")/../iosApp"
fastlane beta
