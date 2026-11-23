#!/bin/bash
# Pushes App Store metadata + screenshots to the editable version (no binary,
# no submit). Runs over non-interactive ssh: scripts/mac.sh 'bash scripts/mac-metadata.sh'.
# LANG/LC_ALL must be UTF-8 — scripts/mac.sh does not set them, and deliver dies
# with "invalid byte sequence in US-ASCII" on the first å/ä/ö in the Swedish
# metadata. Same reason mac-beta.sh sets them.
set -eo pipefail
# UI-test gate (issue #15): refuse unless AppUiTest passed on the phone for exactly this commit
# (the record comes over with scripts/mac-sync.sh). SKIP_UI_GATE=1 overrides, loudly.
sh "$(dirname "$0")/ui-gate.sh"
export PATH=/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
cd "$(dirname "$0")/../iosApp"
fastlane metadata "$@"
