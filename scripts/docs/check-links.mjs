import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import MarkdownIt from 'markdown-it';

export const requiredEntries = [
  'README.md', 'AGENTS.md', 'CHANGELOG.md', 'docs/index.md',
  'docs/chat.md', 'docs/deployment.md', 'docs/observability.md', 'docs/ci.md'
];
const markdown = new MarkdownIt();
// Parse even file: links so policy violations are reported rather than silently dropped.
markdown.validateLink = () => true;
const key = issue => JSON.stringify([issue.source, issue.target, issue.type]);
const managed = file => /^(?:README|AGENTS|CHANGELOG)\.md$/.test(file) || /^docs\/.*\.md$/i.test(file);

function links(tokens) {
  return tokens.flatMap(token => {
    const target = token.type === 'link_open' ? token.attrGet('href')
      : token.type === 'image' ? token.attrGet('src') : null;
    return [...(target === null ? [] : [target]), ...links(token.children ?? [])];
  });
}

export function checkRepository(root, files, baseline = [], entries = requiredEntries) {
  const publicFiles = new Set(files.filter(file => existsSync(path.join(root, file))));
  const issues = new Map();
  const navigation = new Map();
  const add = (source, target, type) => {
    const issue = { source, target, type };
    issues.set(key(issue), issue);
  };
  for (const source of [...publicFiles].filter(managed).sort()) {
    const targets = new Set();
    navigation.set(source, targets);
    for (const href of links(markdown.parse(readFileSync(path.join(root, source), 'utf8'), {}))) {
      if (!href || href.startsWith('#') || href.startsWith('//')) continue;
      let decoded;
      try { decoded = decodeURIComponent(href.split(/[?#]/, 1)[0]); }
      catch { add(source, href, 'invalid-path'); continue; }
      if (/^(?:[a-z]:[/\\]|[/\\]|file:)/i.test(decoded)) {
        add(source, decoded.replaceAll('\\', '/'), 'absolute-path');
        continue;
      }
      // This checker never requests external URLs, including mailto and other schemes.
      if (/^[a-z][a-z\d+.-]*:/i.test(decoded)) continue;
      if (!decoded) continue;
      const target = path.posix.normalize(path.posix.join(path.posix.dirname(source), decoded.replaceAll('\\', '/')));
      targets.add(target);
      const directoryPrefix = target === '.' ? '' : `${target.replace(/\/$/, '')}/`;
      const published = publicFiles.has(target) || [...publicFiles].some(file => file.startsWith(directoryPrefix));
      if (!published || !existsSync(path.join(root, target))) add(source, target, 'missing-target');
    }
  }
  for (const entry of entries) {
    if (!publicFiles.has(entry)) add('(navigation)', entry, 'missing-entry');
  }
  if (entries.length) {
    const edges = [['README.md', 'docs/index.md'], ...entries
      .filter(file => file !== 'README.md' && file !== 'docs/index.md')
      .map(file => ['docs/index.md', file])];
    for (const [source, target] of edges) {
      if (!navigation.get(source)?.has(target)) add(source, target, 'missing-entry-link');
    }
  }
  const exemptions = new Map();
  for (const item of baseline) {
    if (!item.source || !item.target || !item.type || typeof item.reason !== 'string' || !item.reason.trim()) {
      throw new Error('Each baseline entry needs source, target, type and reason');
    }
    if (requiredEntries.includes(item.source) || item.type.startsWith('missing-entry')) {
      throw new Error('Cannot exempt current guides or mandatory navigation');
    }
    if (exemptions.has(key(item))) throw new Error('Duplicate baseline entry');
    exemptions.set(key(item), item);
  }
  return {
    issues: [...issues.values()],
    errors: [...issues.values()].filter(issue => !exemptions.has(key(issue))),
    exempted: [...issues.values()].filter(issue => exemptions.has(key(issue))),
    stale: baseline.filter(issue => !issues.has(key(issue)))
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv.slice(2).some(arg => arg !== '--report')) throw new Error('Usage: node check-links.mjs [--report]');
    const root = fileURLToPath(new URL('../../', import.meta.url));
    const files = execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'], {
      cwd: root, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024
    }).split('\0').filter(Boolean);
    const baseline = JSON.parse(readFileSync(new URL('./link-baseline.json', import.meta.url), 'utf8'));
    const result = checkRepository(root, files, baseline);
    if (process.argv.includes('--report')) {
      console.log(JSON.stringify(result, null, 2));
    } else {
      for (const issue of result.errors) console.error(`${issue.type}: ${issue.source} -> ${issue.target}`);
      for (const issue of result.stale) console.warn(`Remove stale baseline entry: ${issue.source} -> ${issue.target}`);
      console.log(`Docs: ${files.filter(managed).length} files, ${result.errors.length} errors, ${result.exempted.length} historical issues, ${result.stale.length} stale exemptions`);
      process.exitCode = result.errors.length ? 1 : 0;
    }
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
