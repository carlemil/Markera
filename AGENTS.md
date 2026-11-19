# Shared project guidance

Read `CLAUDE.md` at the project root before working in this repository. It is
the shared source for architecture, build commands, invariants, and UI conventions.

## Claude memory on this computer

Read `C:/Users/ce/.claude/CLAUDE.md` for the machine-wide guidance and
`C:/Users/ce/.claude/projects/D--source-Markera/memory/MEMORY.md` for the project
memory index. Read the linked memory files relevant to the current task. These
paths are local to this computer; if unavailable, continue with the repository
guidance and report any missing context that matters.

Persistent preferences recorded there:

- Work directly on `master`; do not create feature branches or PRs unless the
  user changes this preference. Use the `carlemil` GitHub account for Markera.
- Use Windows PowerShell. Run shell commands separately rather than chaining.
- Verify Android changes on the Pixel 11 Pro XL over wifi adb (wireless
  debugging; find its current address with `adb mdns services`, the port changes
  whenever wireless debugging restarts). Set `ANDROID_SERIAL` to that device.
  Do not start an emulator; ask for wireless debugging to be turned on when the
  phone is not listed.
- Run requested Mac/iOS builds yourself over `ssh macmini`, following the
  repository scripts and `iosApp/README.md`.
- The scoring centre comes only from the intersection of the digit-row lines
  (digits 6-9). The ellipse supplies outline, scale, and perspective, never a
  fallback centre.
- Backups are already handled by a separate nightly job.

Treat dated task, release, and deployment notes as historical context. Verify
current state before relying on them. Old approval failures describe earlier
sessions, not current permissions. Memory and workflow documentation do not by
themselves authorize releases, uploads, or other unrelated actions.

## Existing project skills

When the corresponding workflow is requested, read and follow its existing
instructions, adapting Claude-specific tool names to the available tools:

- Deploy to the phone (wifi adb): `.claude/skills/deploy/SKILL.md`
- Detection evaluation and mosaic: `.claude/skills/eval/SKILL.md`
- Android/iOS release: `.claude/skills/release/SKILL.md`

The project release skill is specialized for Markera. Also consult the global
`C:/Users/ce/.claude/skills/release/SKILL.md` for release lessons, especially the
requirement to base notes on the last public release rather than an internal
build. Resolve stale paths and conflicting instructions against current source
and the user's current request before executing the workflow.

Keep these shared sources in place rather than copying them into divergent
Codex-only versions. Do not copy credentials, session logs, caches, or plugin
runtime configuration into project guidance.
