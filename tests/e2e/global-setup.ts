import { request, type FullConfig } from '@playwright/test';
import fs from 'fs';
import path from 'path';

export default async function globalSetup(config: FullConfig): Promise<void> {
  const baseURL = config.projects[0]?.use?.baseURL as string;
  const storageStatePath = path.join(__dirname, '..', '.auth', 'user.json');
  fs.mkdirSync(path.dirname(storageStatePath), { recursive: true });

  const context = await request.newContext({ baseURL });
  const password = process.env.AGENT_E2E_ADMIN_PASSWORD;
  if (!password) {
    throw new Error('AGENT_E2E_ADMIN_PASSWORD is required for E2E database login');
  }
  const response = await context.post('/api/auth/login', {
    data: { username: 'admin', password }
  });
  if (!response.ok()) {
    throw new Error(`E2E login failed: ${response.status()} ${await response.text()}`);
  }
  await context.storageState({ path: storageStatePath });
  await context.dispose();
}
