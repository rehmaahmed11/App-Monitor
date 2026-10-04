// Lightweight Java syntax check: parses every source file with java-parser and
// reports the first syntax error per file. Used as a fast pre-push sanity gate.
//
//   npm i java-parser        (anywhere on NODE_PATH, or set APPLENS_JAVA_PARSER)
//   node tools/jsyntax.mjs [source-root]
import fs from 'fs';
import path from 'path';

const candidates = [
  process.env.APPLENS_JAVA_PARSER,
  path.resolve(process.cwd(), 'node_modules/java-parser/src/index.js'),
  path.resolve(process.cwd(), '../tools/node_modules/java-parser/src/index.js'),
  '/home/user/tools/jcheck/node_modules/java-parser/src/index.js',
].filter(Boolean);

const parserPath = candidates.find((p) => fs.existsSync(p));
if (!parserPath) {
  console.error('java-parser not found. Run `npm i java-parser` or set APPLENS_JAVA_PARSER.');
  process.exit(2);
}
const { parse } = await import('file://' + parserPath);

const root = path.resolve(process.argv[2] || 'app/src/main/java');
const files = [];
(function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full);
    else if (entry.name.endsWith('.java')) files.push(full);
  }
})(root);

let failed = 0;
for (const file of files) {
  const src = fs.readFileSync(file, 'utf8');
  try {
    parse(src);
  } catch (e) {
    failed++;
    console.log('SYNTAX ' + path.relative(root, file) + ' -> ' + String(e.message).split('\n')[0]);
  }
}
console.log(`checked ${files.length} files, ${failed} with syntax errors`);
process.exit(failed ? 1 : 0);
