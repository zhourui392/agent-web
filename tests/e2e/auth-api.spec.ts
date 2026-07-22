import { test, expect } from '@playwright/test';

test('用户名密码登录后返回数据库会话状态', async ({ request }) => {
  const login = await request.post('/api/auth/login', {
    data: { username: 'admin', password: process.env.AGENT_E2E_ADMIN_PASSWORD }
  });
  expect(login.ok()).toBeTruthy();

  const status = await request.get('/api/auth/status');
  expect(status.ok()).toBeTruthy();
  expect(await status.json()).toMatchObject({
    mode: 'manual',
    enforced: true,
    authEnabled: true,
    authenticated: true,
    userId: 'admin',
    username: 'admin',
    role: 'ADMIN'
  });
});

test('远程登录入口已移除', async ({ request }) => {
  const response = await request.get('/api/auth/sso-login-url');
  expect(response.status()).toBe(404);
});
