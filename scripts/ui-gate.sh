#!/bin/sh
# UI-test upload gate (issue #15), the shell twin of :composeApp:checkUiTestGate.
# Exits 0 only when .ui-test-pass at the repo root (written by a passing, whole, clean on-phone
# AppUiTest run; see gradle/ui-test-gate.gradle.kts) names HEAD and the working tree is clean.
#
# Used by scripts/mac.sh before it sends an upload command (Windows side), and on the Mac by
# mac-beta.sh, mac-metadata.sh and the Fastfile's upload lanes; scripts/mac-sync.sh carries the
# record over, and the Mac clone is HEAD's own commit, so the same check holds there.
#
# Usage: sh scripts/ui-gate.sh [repo dir]     (default: the repo this script is in)
# Emergency override: SKIP_UI_GATE=1 (prints a loud warning, exits 0).
repo=${1:-"$(dirname "$0")/.."}
cmd='$env:ANDROID_SERIAL="<phone serial>"; .\gradlew.bat :composeApp:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=se.kjellstrand.markera.AppUiTest"'

if [ "$SKIP_UI_GATE" = 1 ] || [ "$SKIP_UI_GATE" = true ]; then
    {
        echo '!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!'
        echo '!! UI-TEST GATE SKIPPED (SKIP_UI_GATE).'
        echo '!! This upload is NOT covered by a passing on-phone AppUiTest run.'
        echo '!! Emergencies only: run AppUiTest on the phone as soon as possible.'
        echo '!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!'
    } >&2
    exit 0
fi

head=$(git -C "$repo" rev-parse HEAD) || { echo "UI gate: $repo is not a git checkout" >&2; exit 1; }
record="$repo/.ui-test-pass"
recorded=
[ -f "$record" ] && recorded=$(head -n 1 "$record" | tr -d '\r ')
dirty=$(git -C "$repo" status --porcelain)

problems=
if [ -z "$recorded" ]; then
    problems="  - no passing AppUiTest run is recorded (.ui-test-pass is missing)"
elif [ "$recorded" != "$head" ]; then
    problems="  - the recorded AppUiTest pass is for $recorded, but HEAD is $head"
fi
if [ -n "$dirty" ]; then
    problems="$problems${problems:+
}  - the working tree has uncommitted changes:
$(printf '%s\n' "$dirty" | head -n 5 | sed 's/^/      /')"
fi

if [ -n "$problems" ]; then
    {
        echo "Upload refused: the UI tests have not passed on the phone for exactly this source."
        printf '%s\n' "$problems"
        echo "Commit everything, then run on the phone (PowerShell):"
        echo "  $cmd"
        echo "and, for the Mac, sh scripts/mac-sync.sh to carry the record over."
        echo "Emergency override (loud warning): SKIP_UI_GATE=1"
    } >&2
    exit 1
fi
echo "UI gate: AppUiTest passed on $head; upload allowed."
