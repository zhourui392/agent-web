import { request, type FullConfig } from '@playwright/test';
import fs from 'fs';
import path from 'path';

export default async function globalSetup(config: FullConfig): Promise<void> {
  const baseURL = config.projects[0]?.use?.baseURL as string;
  const storageStatePath = path.join(__dirname, '..', '.auth', 'user.json');
  fs.mkdirSync(path.dirname(storageStatePath), { recursive: true });

  const context = await request.newContext({ baseURL });
  const response = await context.post('/api/auth/manual-login', {
    data: { employeeId: 'E10001', userName: 'E2E User' }
  });
  if (!response.ok()) {
    throw new Error(`E2E login failed: ${response.status()} ${await response.text()}`);
  }
  await context.storageState({ path: storageStatePath });
  await context.dispose();
}
