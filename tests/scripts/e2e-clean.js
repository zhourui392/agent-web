const fs = require('fs');
const path = require('path');

const repoRoot = path.resolve(__dirname, '../..');
const dbPaths = [
  path.join(repoRoot, 'data', 'agent-web-e2e.db'),
  path.join(repoRoot, 'data', 'agent-web-e2e-qa.db'),
  path.join(repoRoot, 'data', 'agent-web-harness-e2e.db'),
];
const suffixes = ['', '-journal', '-wal', '-shm'];

for (const dbPath of dbPaths) {
  for (const suffix of suffixes) {
    fs.rmSync(dbPath + suffix, { force: true });
  }
  console.log('Removed e2e SQLite files under ' + path.relative(repoRoot, dbPath));
}

fs.rmSync(path.join(repoRoot, 'data', 'harness-e2e'), { recursive: true, force: true });
console.log('Removed Harness e2e artifacts under data/harness-e2e');

for (const output of ['test-results', 'playwright-report', 'playwright-report-harness']) {
  fs.rmSync(path.join(repoRoot, 'tests', output), { recursive: true, force: true });
}
console.log('Removed Playwright result directories under tests');
