import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { mkdtempSync, writeFileSync, unlinkSync, rmdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const require = createRequire(import.meta.url);
const minimatchPath = require.resolve('minimatch');

// Exercise the actual transitive glob engine in bounded isolated children.
// These are build-input regression cases, not claims of a remote HTTP exploit.
for (const [name, input] of [
  ['nested single set', '{'.repeat(4000) + 'a,b' + '}'.repeat(4000)],
  ['nested comma alternatives', '{a,'.repeat(4000) + 'z' + '}'.repeat(4000)],
]) {
  test(`build glob engine handles ${name} without stack exhaustion`, () => {
    const child = spawnSync(process.execPath, ['-e', `
      const { braceExpand, minimatch } = require(process.argv[1]);
      const input = process.argv[2];
      const expansions = braceExpand(input);
      if (!Array.isArray(expansions) || expansions.length === 0) process.exit(2);
      if (typeof minimatch('data-probe', input) !== 'boolean') process.exit(3);
      process.stdout.write(JSON.stringify({ bounded: true, results: expansions.length }));
    `, minimatchPath, input], { encoding: 'utf8', timeout: 5000, maxBuffer: 16384, windowsHide: true });
    assert.ifError(child.error);
    assert.equal(child.signal, null);
    assert.equal(child.status, 0, child.stderr);
    assert.equal(JSON.parse(child.stdout).bounded, true);
  });
}

test('normal Vue compiler attribute globs and include patterns keep their meaning', () => {
  const { braceExpand, minimatch } = require('minimatch');
  assert.equal(minimatch('data-probe', 'data-*'), true);
  assert.equal(minimatch('aria-label', 'data-*'), false);
  assert.equal(minimatch('src/pages/OnCallPage.vue', 'src/**/*.vue'), true);
  assert.equal(minimatch('src/pages/OnCallPage.ts', 'src/**/*.vue'), false);
  assert.deepEqual(braceExpand('src/**/*.{ts,tsx,vue}'), ['src/**/*.ts', 'src/**/*.tsx', 'src/**/*.vue']);
  assert.deepEqual(braceExpand('data-{probe,status}'), ['data-probe', 'data-status']);
});

test('actual Vue compiler does not silently print a glob stack error with exit zero', () => {
  const webRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
  const directory = mkdtempSync(path.join(tmpdir(), 'opspilot-vue-glob-'));
  const probe = path.join(directory, 'Probe.vue');
  const config = path.join(directory, 'tsconfig.json');
  try {
    writeFileSync(probe, '<template><div data-probe="ok">bounded compiler fixture</div></template>');
    writeFileSync(config, JSON.stringify({
      extends: path.join(webRoot, 'tsconfig.app.json'), include: ['./Probe.vue'],
      compilerOptions: { paths: { vue: [path.join(webRoot, 'node_modules/vue/dist/vue.d.ts')] } },
      vueCompilerOptions: { dataAttributes: ['{'.repeat(4000) + 'a,b' + '}'.repeat(4000)] },
    }));
    const child = spawnSync(process.execPath, [require.resolve('vue-tsc/bin/vue-tsc.js'), '--noEmit', '--project', config],
      { encoding: 'utf8', timeout: 10000, maxBuffer: 32768, windowsHide: true });
    assert.ifError(child.error);
    assert.equal(child.signal, null);
    assert.equal(child.status, 0, child.stderr + child.stdout);
    assert.doesNotMatch(child.stderr + child.stdout, /RangeError|Maximum call stack|error TS\d+/);
  } finally {
    // Only the two files and directory created by this test; no recursive cleanup.
    for (const file of [probe, config]) { try { unlinkSync(file); } catch (error) { if (error.code !== 'ENOENT') throw error; } }
    rmdirSync(directory);
  }
});
