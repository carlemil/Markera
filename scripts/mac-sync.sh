#!/bin/sh
# Mirror this working tree (HEAD + uncommitted changes) onto the Mac mini clone
# at ~/source/Markera, where the iOS app is built. The Mac never commits: HEAD
# goes over as branch `sync` (checked out there as `build`), and modified/new
# files ride along as a tar stream. Run from Git Bash on Windows.
set -e
cd "$(dirname "$0")/.."

git remote get-url macmini >/dev/null 2>&1 || git remote add macmini macmini:source/Markera
git push -q -f macmini HEAD:refs/heads/sync
ssh macmini 'cd ~/source/Markera && git checkout -qf -B build sync && git clean -qfd'

files=$(git ls-files -mo --exclude-standard)
if [ -n "$files" ]; then
    git ls-files -moz --exclude-standard | tar --null -cf - -T - |
        ssh macmini 'tar -C ~/source/Markera -xf -'
fi
deleted=$(git ls-files -d)
if [ -n "$deleted" ]; then
    printf '%s\n' "$deleted" | ssh macmini 'cd ~/source/Markera && xargs rm -f'
fi
echo "Mac at $(git rev-parse --short HEAD) + $(printf '%s' "$files" | grep -c .) uncommitted file(s)"
