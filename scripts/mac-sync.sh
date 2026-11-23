#!/bin/sh
set -e
cd "$(dirname "$0")/.."
git remote get-url macmini >/dev/null 2>&1 || git remote add macmini macmini:source/Markera
git push -q -f macmini HEAD:refs/heads/sync
ssh macmini 'cd ~/source/Markera && git checkout -qf -B build sync && git clean -qfd'
# Everything that differs from HEAD in the working tree: modified, untracked,
# and staged adds/renames whose content matches the index (which
# `git ls-files -m` would miss).
files=$( (git diff --name-only --no-renames --diff-filter=AM HEAD; git ls-files -o --exclude-standard) | sort -u)
if [ -n "$files" ]; then
    printf '%s\n' "$files" | tar -cf - -T - |
        ssh macmini 'tar -C ~/source/Markera -xf -'
fi
# Deleted or renamed away, staged or not.
deleted=$(git diff --name-only --no-renames --diff-filter=D HEAD)
if [ -n "$deleted" ]; then
    printf '%s\n' "$deleted" | ssh macmini 'cd ~/source/Markera && xargs rm -f'
fi
# The UI-test gate record (gitignored, so neither the push nor the tar above carries it): the Mac's
# upload lanes check it against the Mac's HEAD (scripts/ui-gate.sh). No record here, none there.
if [ -f .ui-test-pass ]; then
    ssh macmini 'cat > ~/source/Markera/.ui-test-pass' < .ui-test-pass
else
    ssh macmini 'rm -f ~/source/Markera/.ui-test-pass'
fi
echo "Mac at $(git rev-parse --short HEAD) + $(printf '%s' "$files" | grep -c .) uncommitted file(s)"
