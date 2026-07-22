import { test, expect } from '@playwright/test';

test('用户管理: 管理员创建普通用户后，新用户可以登录', async ({ page }) => {
  const marker = Date.now().toString();
  const username = `e2e-user-${marker}`;
  const password = `E2e-password-${marker}!`;

  await page.goto('/admin/users.html');
  await expect(page.getByRole('menuitem', { name: '用户管理' })).toBeVisible({ timeout: 10_000 });
  await page.getByRole('button', { name: '新增用户' }).click();
  await page.getByPlaceholder('请输入用户名').fill(username);
  await page.getByPlaceholder('至少 12 个字符').fill(password);
  await page.locator('[data-test="user-role-select"]').click();
  await page.getByRole('option', { name: '普通用户' }).click();
  await page.getByRole('button', { name: '创建' }).click();

  await expect(page.getByText('用户创建成功')).toBeVisible();
  await expect(page.getByRole('cell', { name: username })).toBeVisible();

  const loginResponse = await page.request.post('/api/auth/login', {
    data: { username, password }
  });
  expect(loginResponse.ok()).toBeTruthy();
  const statusResponse = await page.request.get('/api/auth/status');
  expect(await statusResponse.json()).toMatchObject({
    authenticated: true,
    username,
    role: 'USER'
  });
});
