#!/usr/bin/env node
// Tests for signposts.mjs's orphan cleanup (PR #67, issue #49): `--sync`, the per-file mode and the PostToolUse
// (stdin JSON) mode delete only signposts the script generated, never a hidden `.X.h` that merely matches the name
// pattern. Runs the script as a child process against a throwaway git repo.
//   node .claude/hooks/signposts.test.mjs      exits 1 on the first failure

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'signposts.mjs');

/** Runs the script with the temp repo as its root (CLAUDE_PROJECT_DIR wins over cwd, so set both). */
function run(root, args, stdin = '') {
  execFileSync(process.execPath, [SCRIPT, ...args], {
    cwd: root,
    env: { ...process.env, CLAUDE_PROJECT_DIR: root },
    input: stdin,
    encoding: 'utf8',
  });
}

const git = (root, ...args) =>
  execFileSync('git', ['-c', 'core.autocrlf=false', ...args], { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });

/**
 * A repo with one live source, one generated orphan and the three hidden `.h` files that must survive:
 * a headerless `.foo.h`, a headerless `.notes.kt.h`, and `.other.kt.h` carrying a real header for another name.
 */
function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'signposts-test-'));
  const src = path.join(root, 'src');
  fs.mkdirSync(src);
  git(root, 'init', '-q');
  fs.writeFileSync(path.join(root, '.gitignore'), '.*.h\n');
  fs.writeFileSync(path.join(src, 'live.kt'), 'fun live() = 1\n');
  fs.writeFileSync(path.join(src, 'gone.kt'), 'fun gone() = 2\n');
  fs.writeFileSync(path.join(src, 'different.kt'), 'class Different\n');
  run(root, [path.join(src, 'gone.kt'), path.join(src, 'different.kt')]);
  const p = name => path.join(src, name);
  assert.ok(fs.existsSync(p('.gone.kt.h')), 'setup: the script did not generate .gone.kt.h');
  // A generated header, but for `different.kt`: not this file's own, so not ours to delete.
  fs.copyFileSync(p('.different.kt.h'), p('.other.kt.h'));
  fs.rmSync(p('.different.kt.h'));
  fs.rmSync(p('different.kt'));
  fs.rmSync(p('gone.kt'));
  fs.writeFileSync(p('.foo.h'), '#pragma once\nint foo(void);\n');
  fs.writeFileSync(p('.notes.kt.h'), 'my own notes about notes.kt\n');
  git(root, 'add', '-A');
  return { root, p };
}

function assertKeepers(p) {
  assert.equal(fs.readFileSync(p('.foo.h'), 'utf8'), '#pragma once\nint foo(void);\n', '.foo.h was touched');
  assert.equal(fs.readFileSync(p('.notes.kt.h'), 'utf8'), 'my own notes about notes.kt\n', '.notes.kt.h was touched');
  assert.match(fs.readFileSync(p('.other.kt.h'), 'utf8'), /^# different\.kt: /, '.other.kt.h was touched');
}

const cases = {
  'sync deletes the generated orphan, keeps the three foreign .h files and writes the live signpost'() {
    const { root, p } = fixture();
    try {
      run(root, ['--sync']);
      assert.ok(!fs.existsSync(p('.gone.kt.h')), 'the generated orphan survived --sync');
      assert.match(fs.readFileSync(p('.live.kt.h'), 'utf8'), /^# live\.kt: \d+ lines · signposts · sha1 /, 'no signpost for the live source');
      assertKeepers(p);
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
  },

  'per-file mode deletes the generated orphan and keeps the foreign .h files'() {
    const { root, p } = fixture();
    try {
      run(root, ['src/gone.kt', 'src/notes.kt', 'src/other.kt', 'src/foo']);
      assert.ok(!fs.existsSync(p('.gone.kt.h')), 'the generated orphan survived the per-file mode');
      assertKeepers(p);
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
  },

  'hook mode (stdin JSON) deletes only a generated orphan'() {
    const { root, p } = fixture();
    try {
      for (const name of ['gone.kt', 'notes.kt', 'other.kt']) {
        run(root, [], JSON.stringify({ tool_input: { file_path: p(name) } }));
      }
      assert.ok(!fs.existsSync(p('.gone.kt.h')), 'the generated orphan survived the hook mode');
      assertKeepers(p);
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
  },
};

let failed = 0;
for (const [name, fn] of Object.entries(cases)) {
  try { fn(); console.log('ok   ' + name); }
  catch (e) { failed++; console.log('FAIL ' + name + '\n     ' + (e && e.message)); }
}
console.log(failed ? `${failed} of ${Object.keys(cases).length} failed` : `all ${Object.keys(cases).length} passed`);
process.exit(failed ? 1 : 0);
