import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const options = { module: ts.ModuleKind.ES2022, target: ts.ScriptTarget.ES2022 };
const parser = ts.transpileModule(readFileSync(resolve(root, 'frontend/src/utils/sse.ts'), 'utf8'), {
  compilerOptions: options
});
const transport = ts.transpileModule(readFileSync(resolve(root, 'frontend/src/service/api/chat-stream.ts'), 'utf8'), {
  compilerOptions: options
});
const source = transport.outputText.replace(/^import .+ from ['"]\.\.\/\.\.\/utils\/sse['"];\r?\n/m, '');
if (/^import /m.test(source)) throw new Error('Standalone transport has an unexpected dependency');
writeFileSync(
  resolve(root, 'src/main/resources/static/chat-stream.mjs'),
  `// Generated from frontend SSE transport by frontend/scripts/sync-chat-transport.mjs.\n${parser.outputText}${source}`
);
