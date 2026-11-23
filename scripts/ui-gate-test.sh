#!/bin/sh
# Tests for scripts/ui-gate.sh in a throwaway git repo (removed afterwards): sh scripts/ui-gate-test.sh
gate="$(cd "$(dirname "$0")" && pwd)/ui-gate.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0

expect() { # expect <exit code> <name> [env assignment]
    want=$1 name=$2
    if [ -n "$3" ]; then out=$(env "$3" sh "$gate" "$tmp" 2>&1); else out=$(env -u SKIP_UI_GATE sh "$gate" "$tmp" 2>&1); fi
    got=$?
    if [ "$got" = "$want" ]; then echo "ok   $name"; else echo "FAIL $name (exit $got, wanted $want)"; printf '%s\n' "$out" | sed 's/^/     /'; fails=$((fails + 1)); fi
}

git -C "$tmp" init -q
git -C "$tmp" config user.email gate@test
git -C "$tmp" config user.name gate
printf '/.ui-test-pass\n' > "$tmp/.gitignore"
git -C "$tmp" add .gitignore
git -C "$tmp" commit -qm one
first=$(git -C "$tmp" rev-parse HEAD)

expect 1 "no record"
printf '%s\nclass=se.kjellstrand.markera.AppUiTest\n' "$first" > "$tmp/.ui-test-pass"
expect 0 "matching record, clean tree"
printf '%s\r\nclass=x\r\n' "$first" > "$tmp/.ui-test-pass"
expect 0 "matching record written with CRLF"
echo change > "$tmp/file"
expect 1 "dirty tree (untracked file)"
git -C "$tmp" add file
expect 1 "dirty tree (staged file)"
git -C "$tmp" commit -qm two
expect 1 "stale record (HEAD moved on)"
expect 0 "SKIP_UI_GATE=1 overrides" SKIP_UI_GATE=1
expect 1 "SKIP_UI_GATE=0 does not" SKIP_UI_GATE=0
printf '\n' > "$tmp/.ui-test-pass"
expect 1 "empty record"

[ "$fails" = 0 ] && echo "all ui-gate tests passed" || { echo "$fails ui-gate test(s) failed"; exit 1; }
