const fs = require('fs');
const path = require('path');

const repoRoot = path.resolve(__dirname, '../..');
const dbPaths = process.env.AGENT_E2E_SKIP_SHARED_DATABASE_CLEANUP === 'true'
  ? []
  : [
      path.join(repoRoot, 'data', 'agent-web-e2e.db'),
      path.join(repoRoot, 'data', 'agent-web-e2e-qa.db'),
    ];
const suffixes = ['', '-journal', '-wal', '-shm'];

for (const dbPath of dbPaths) {
  for (const suffix of suffixes) {
    fs.rmSync(dbPath + suffix, { force: true });
  }
  console.log('Removed e2e SQLite files under ' + path.relative(repoRoot, dbPath));
}

for (const output of ['test-results', 'playwright-report']) {
  fs.rmSync(path.join(repoRoot, 'tests', output), { recursive: true, force: true });
}
console.log('Removed Playwright result directories under tests');
