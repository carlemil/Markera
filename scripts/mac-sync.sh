#!/bin/sh
# Mirrors HEAD plus this checkout's uncommitted files onto the Mac clone.
#
# The Mac clone is a mirror, not a workspace: every sync force-checks-out HEAD
# and cleans it. So it first makes sure nothing was changed there since the
# last sync (a hand fix, an untracked fastlane screenshot) and refuses with a
# list if something was. FORCE=1 overrides and discards those changes.
#
# How "changed" is judged: a sync itself leaves the Mac tree dirty on purpose
# (it copies this checkout's uncommitted files over), so plain `git status`
# can't tell. Instead, the end of each sync records a snapshot of the Mac's
# whole working tree (tracked + untracked, minus gitignored) as a git tree id
# in .git/mac-sync-state. The next sync takes the same snapshot and compares;
# any difference is a change made on the Mac. With no record (first run of
# this version, or an interrupted sync) it compares against HEAD instead.
set -e
cd "$(dirname "$0")/.."
git remote get-url macmini >/dev/null 2>&1 || git remote add macmini macmini:source/Markera
# The remote is `host:dir`; dir is relative to the Mac home unless absolute.
url=$(git remote get-url macmini)
host=${url%%:*}
dir=${url#*:}
case $dir in /*) ;; *) dir="~/$dir" ;; esac
git push -q -f macmini HEAD:refs/heads/sync
# Gitignored files that fastlane needs on the Mac: the App Review contact
# (issue #8). The checkout below deletes them there when it moves off a commit
# that still tracked them, so they are set aside first and put back if this
# checkout has no copy of its own to send.
review=iosApp/fastlane/metadata/review_information
# Prints the git tree id of the Mac working tree, without touching its index.
snapshot='snapshot() {
    idx=$(git rev-parse --git-path mac-sync-index)
    cp "$(git rev-parse --git-path index)" "$idx" &&
        GIT_INDEX_FILE=$idx git add -A -- . ":(exclude).sync-keep" &&
        GIT_INDEX_FILE=$idx git write-tree
    rc=$?
    rm -f "$idx"
    return $rc
}'
ssh "$host" "cd $dir && sh -s -- '${FORCE:-0}' '$review'" <<EOF
set -e
force=\$1 review=\$2
$snapshot
state=\$(git rev-parse --git-path mac-sync-state)
now=\$(snapshot)
if [ -s "\$state" ]; then
    was=\$(cat "\$state") since="the last sync"
else
    was=\$(git rev-parse 'HEAD^{tree}') since="HEAD (no record of a previous sync)"
fi
if [ "\$now" != "\$was" ]; then
    if [ "\$force" = 1 ]; then
        echo "FORCE=1: discarding these changes on the Mac since \$since:" >&2
    else
        echo "The Mac clone (\$(pwd)) has changes since \$since:" >&2
    fi
    git diff --name-status --no-renames "\$was" "\$now" >&2
    if [ "\$force" != 1 ]; then
        echo "Copy or commit them first, or rerun with FORCE=1 to discard them." >&2
        [ -s "\$state" ] || echo "(If they are just the files a previous sync copied over, FORCE=1 is safe.)" >&2
        exit 3
    fi
fi
# Until this sync completes, there is no trusted record.
rm -f "\$state"
rm -rf .sync-keep
mkdir .sync-keep
[ ! -d "\$review" ] || cp -p "\$review"/*.txt .sync-keep/ 2>/dev/null || true
git checkout -qf -B build sync
git clean -qfd -e .sync-keep
mkdir -p "\$review"
for f in .sync-keep/*.txt; do
    [ -e "\$f" ] || continue
    t=\$review/\$(basename "\$f")
    [ -e "\$t" ] || cp -p "\$f" "\$t"
done
rm -rf .sync-keep
EOF
# Everything that differs from HEAD in the working tree: modified, untracked,
# and staged adds/renames whose content matches the index (which
# `git ls-files -m` would miss), plus the ignored review contact files.
# NUL-separated throughout, so names with spaces or newlines survive.
changed() {
    { git diff -z --name-only --no-renames --diff-filter=AM HEAD
      git ls-files -z -o --exclude-standard
      git ls-files -z -o -i --exclude-standard -- "$review"
    } | sort -zu
}
count=$(changed | tr -cd '\0' | wc -c)
count=$((count))
if [ "$count" -gt 0 ]; then
    changed | tar --null -cf - -T - |
        ssh "$host" "tar -C $dir -xf -"
fi
# Deleted or renamed away, staged or not.
if [ -n "$(git diff --name-only --no-renames --diff-filter=D HEAD)" ]; then
    git diff -z --name-only --no-renames --diff-filter=D HEAD |
        ssh "$host" "cd $dir && xargs -0 rm -f --"
fi
# Record what the Mac tree looks like now, for the next sync's check.
ssh "$host" "cd $dir && sh -s" <<EOF
set -e
$snapshot
snapshot > "\$(git rev-parse --git-path mac-sync-state)"
EOF
echo "Mac at $(git rev-parse --short HEAD) + $count uncommitted file(s)"
