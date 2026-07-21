import { test, expect } from '@playwright/test';

test('工号登录后返回本地会话状态', async ({ request }) => {
  const login = await request.post('/api/auth/manual-login', {
    data: { employeeId: 'E10001', userName: '测试用户' }
  });
  expect(login.ok()).toBeTruthy();

  const status = await request.get('/api/auth/status');
  expect(status.ok()).toBeTruthy();
  expect(await status.json()).toMatchObject({
    mode: 'manual',
    enforced: true,
    authEnabled: true,
    authenticated: true,
    userId: 'E10001',
    username: '测试用户'
  });
});

test('远程登录入口已移除', async ({ request }) => {
  const response = await request.get('/api/auth/sso-login-url');
  expect(response.status()).toBe(404);
});
