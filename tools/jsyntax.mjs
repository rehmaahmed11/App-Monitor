// Lightweight Java syntax check: parses every source file with java-parser and
// reports the first syntax error per file. Used as a fast pre-push sanity gate.
import fs from 'fs';
import path from 'path';
import { parse } from '/home/user/tools/jcheck/node_modules/java-parser/src/index.js';

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
