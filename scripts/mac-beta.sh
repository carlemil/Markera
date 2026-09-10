#!/bin/bash
# Run from Windows in an interactive terminal (the keychain unlock prompts for
# the Mac login password; codesign needs the login keychain unlocked in the
# same session, and a non-interactive ssh cannot unlock it):
#   ssh -t macmini bash source/Markera/scripts/mac-beta.sh
# Builds a signed App Store archive and uploads it to TestFlight.
set -eo pipefail
export PATH=/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
security unlock-keychain "$HOME/Library/Keychains/login.keychain-db"
cd "$(dirname "$0")/../iosApp"
fastlane beta
