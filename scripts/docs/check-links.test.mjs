import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, unlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { checkRepository, requiredEntries } from './check-links.mjs';

function fixture(t, contents) {
  const root = mkdtempSync(path.join(tmpdir(), 'shadowrag-docs-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  for (const [file, text] of Object.entries(contents)) {
    mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    writeFileSync(path.join(root, file), text);
  }
  return root;
}
function check(root, files, baseline = []) {
  return checkRepository(root, files, baseline, []);
}

test('new missing links fail and removal is detected in unchanged sources', t => {
  const files = ['docs/source.md', 'docs/target.md'];
  const root = fixture(t, { 'docs/source.md': '[target](target.md)', 'docs/target.md': '# target' });
  assert.equal(check(root, files).errors.length, 0);
  unlinkSync(path.join(root, 'docs/target.md'));
  assert.deepEqual(check(root, files).errors, [
    { source: 'docs/source.md', target: 'docs/target.md', type: 'missing-target' }
  ]);
});

test('references, encoded Chinese and spaces, images and nested lists are checked', t => {
  const root = fixture(t, {
    'docs/a.md': '- [ref][guide]\n\n[guide]: <%E4%B8%AD%E6%96%87%20guide.md#heading>\n\n![img](pic.png)\n',
    'docs/中文 guide.md': '# guide', 'docs/pic.png': 'image'
  });
  const files = ['docs/a.md', 'docs/中文 guide.md', 'docs/pic.png'];
  assert.equal(check(root, files).errors.length, 0);
  unlinkSync(path.join(root, 'docs/pic.png'));
  assert.equal(check(root, files).errors[0].target, 'docs/pic.png');
});

test('code examples, external URLs and anchors do not cause checks or network calls', t => {
  const root = fixture(t, { 'docs/a.md': [
    '```md', '[missing](missing.md)', '```',
    '    [also missing](absent.md)',
    '`[inline](no.md)`', '[web](https://example.invalid)', '[anchor](#missing)',
    '[mail](mailto:nobody@example.invalid)'
  ].join('\n\n') });
  assert.equal(check(root, ['docs/a.md']).errors.length, 0);
});

test('absolute machine links fail on every operating system', t => {
  const root = fixture(t, { 'docs/a.md': '[win](E:/repo/a.md) [unix](/home/me/a.md) [file](file:///tmp/a.md)' });
  const result = check(root, ['docs/a.md']);
  assert.equal(result.errors.length, 3);
  assert.ok(result.errors.every(error => error.type === 'absolute-path'));
});

test('existing but ignored/private files and paths outside the repository cannot satisfy links', t => {
  const root = fixture(t, { 'docs/a.md': '[private](../private.yml) [escape](../../outside.md)', 'private.yml': 'private' });
  assert.equal(check(root, ['docs/a.md']).errors.length, 2);
});

test('directories with public files are allowed as history navigation', t => {
  const root = fixture(t, { 'docs/a.md': '[history](history/)', 'docs/history/old.md': '# old' });
  assert.equal(check(root, ['docs/a.md', 'docs/history/old.md']).errors.length, 0);
});

test('a baseline exempts only the exact source, target and type', t => {
  const files = ['docs/history.md'];
  const root = fixture(t, { 'docs/history.md': '[old](old.md) [new](new.md)' });
  const baseline = [{ source: 'docs/history.md', target: 'docs/old.md', type: 'missing-target', reason: 'Historical artifact unavailable' }];
  const result = check(root, files, baseline);
  assert.equal(result.exempted.length, 1);
  assert.deepEqual(result.errors, [{ source: 'docs/history.md', target: 'docs/new.md', type: 'missing-target' }]);
});

test('current guides cannot be exempted and baseline entries need a reason', t => {
  const root = fixture(t, { 'docs/chat.md': '[missing](old.md)' });
  const issue = { source: 'docs/chat.md', target: 'docs/old.md', type: 'missing-target', reason: 'Old' };
  assert.throws(() => check(root, ['docs/chat.md'], [issue]), /current|当前/);
  assert.throws(() => check(root, ['docs/chat.md'], [{ ...issue, source: 'docs/history.md', reason: '' }]), /reason|原因/);
});

test('fixed historical issues are reported as stale baseline entries', t => {
  const root = fixture(t, { 'docs/a.md': '# clean' });
  const issue = { source: 'docs/a.md', target: 'docs/old.md', type: 'missing-target', reason: 'Old' };
  assert.deepEqual(check(root, ['docs/a.md'], [issue]).stale, [issue]);
});

test('missing mandatory entries and disconnected navigation fail', t => {
  const contents = Object.fromEntries(requiredEntries.map(file => [file, '# entry']));
  const root = fixture(t, contents);
  const result = checkRepository(root, Object.keys(contents), []);
  assert.ok(result.errors.some(error => error.type === 'missing-entry-link' && error.source === 'README.md'));
  unlinkSync(path.join(root, 'AGENTS.md'));
  assert.ok(checkRepository(root, Object.keys(contents), []).errors.some(error => error.type === 'missing-entry' && error.target === 'AGENTS.md'));
});

test('readme to index to current guides satisfies mandatory navigation', t => {
  const contents = Object.fromEntries(requiredEntries.map(file => [file, '# entry']));
  contents['README.md'] = '[docs](docs/index.md)';
  contents['docs/index.md'] = requiredEntries.filter(file => file !== 'README.md' && file !== 'docs/index.md')
    .map(file => `[entry](${path.posix.relative('docs', file)})`).join('\n');
  const root = fixture(t, contents);
  assert.equal(checkRepository(root, Object.keys(contents), []).errors.length, 0);
});
