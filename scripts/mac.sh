#!/bin/sh
# Run a command in the Mac mini clone with Homebrew on PATH (non-interactive
# ssh has no shell profile). Example: scripts/mac.sh sh gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
#
# A store upload (mac-beta.sh, mac-metadata.sh, fastlane beta/metadata/release/submit/reupload)
# first passes the UI-test gate here, before anything is sent (scripts/ui-gate.sh); the Mac
# checks again. SKIP_UI_GATE=1, set locally or inside the command, skips both, loudly.
case "$*" in
    *SKIP_UI_GATE=1*) SKIP_UI_GATE=1 ;;
esac
case "$*" in
    *mac-beta.sh*|*mac-metadata.sh*|*"fastlane beta"*|*"fastlane metadata"*|*"fastlane release"*|\
    *"fastlane submit"*|*"fastlane reupload"*)
        SKIP_UI_GATE=$SKIP_UI_GATE sh "$(dirname "$0")/ui-gate.sh" || exit 1 ;;
esac
skip=
case "$SKIP_UI_GATE" in 1|true) skip='export SKIP_UI_GATE=1; ' ;; esac
exec ssh macmini "export PATH=/opt/homebrew/bin:\$PATH; ${skip}cd ~/source/Markera && $*"
