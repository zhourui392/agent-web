import * as fs from 'fs/promises';
import * as os from 'os';
import * as path from 'path';

export async function makeTempDir(prefix: string): Promise<string> {
  return makeTempDirIn(os.tmpdir(), prefix);
}

export async function makeTempDirIn(parentDir: string, prefix: string): Promise<string> {
  const dir = path.join(parentDir, prefix + '-' + Date.now() + '-' + Math.random().toString(16).slice(2));
  await fs.mkdir(dir, { recursive: true });
  return dir;
}

export async function writeTempFile(dir: string, name: string, content: string): Promise<string> {
  const file = path.join(dir, name);
  await fs.writeFile(file, content, 'utf-8');
  return file;
}

export async function cleanupDir(dir: string | undefined): Promise<void> {
  if (!dir) {
    return;
  }
  await fs.rm(dir, { recursive: true, force: true }).catch(() => {
    // 临时目录清理失败不影响测试结论。
  });
}
