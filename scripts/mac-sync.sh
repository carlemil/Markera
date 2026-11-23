#!/bin/sh
set -e
cd "$(dirname "$0")/.."
git remote get-url macmini >/dev/null 2>&1 || git remote add macmini macmini:source/Markera
git push -q -f macmini HEAD:refs/heads/sync
# Gitignored files that fastlane needs on the Mac: the App Review contact
# (issue #8). The checkout below deletes them there when it moves off a commit
# that still tracked them, so they are set aside first and put back if this
# checkout has no copy of its own to send.
review=iosApp/fastlane/metadata/review_information
ssh macmini "cd ~/source/Markera && rm -rf .sync-keep && mkdir .sync-keep &&
    { [ ! -d $review ] || cp -p $review/*.txt .sync-keep/ 2>/dev/null || true; } &&
    git checkout -qf -B build sync && git clean -qfd -e .sync-keep &&
    mkdir -p $review &&
    for f in .sync-keep/*.txt; do [ -e \"\$f\" ] || continue; t=$review/\$(basename \"\$f\"); [ -e \"\$t\" ] || cp -p \"\$f\" \"\$t\"; done &&
    rm -rf .sync-keep"
# Everything that differs from HEAD in the working tree: modified, untracked,
# and staged adds/renames whose content matches the index (which
# `git ls-files -m` would miss), plus the ignored review contact files.
files=$( (git diff --name-only --no-renames --diff-filter=AM HEAD; git ls-files -o --exclude-standard; git ls-files -o -i --exclude-standard -- "$review") | sort -u)
if [ -n "$files" ]; then
    printf '%s\n' "$files" | tar -cf - -T - |
        ssh macmini 'tar -C ~/source/Markera -xf -'
fi
# Deleted or renamed away, staged or not.
deleted=$(git diff --name-only --no-renames --diff-filter=D HEAD)
if [ -n "$deleted" ]; then
    printf '%s\n' "$deleted" | ssh macmini 'cd ~/source/Markera && xargs rm -f'
fi
echo "Mac at $(git rev-parse --short HEAD) + $(printf '%s' "$files" | grep -c .) uncommitted file(s)"
