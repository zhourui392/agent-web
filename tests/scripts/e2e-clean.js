const fs = require('fs');
const path = require('path');

const repoRoot = path.resolve(__dirname, '../..');
const dbPaths = [
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
