#!/bin/sh
# After scripts/mac-beta.sh: fetch the dSYMs of the archive it just built on the Mac
# mini and upload them to Sentry, so iOS crash reports are symbolicated. Runs on the
# Windows side (Git sh), where sentry.properties (gitignored, holds the auth token)
# and node live; the token never goes to the Mac.
set -e
cd "$(dirname "$0")/.."
[ -f sentry.properties ] || { echo "sentry.properties missing: nothing uploaded"; exit 1; }
mkdir -p build/dsym
scp -q macmini:source/Markera/iosApp/build/ios/iosApp.app.dSYM.zip build/dsym/
root=$(pwd -W 2>/dev/null || pwd)   # sentry-cli is a Windows binary under Git sh
SENTRY_PROPERTIES="$root/sentry.properties" npx --yes @sentry/cli debug-files upload "$root/build/dsym/iosApp.app.dSYM.zip"
