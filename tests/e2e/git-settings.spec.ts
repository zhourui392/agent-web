import { test, expect } from '@playwright/test';

test('Git 设置: 非默认用户保存后刷新回显,凭证只返回脱敏状态', async ({ page }) => {
  const marker = 'E2E-GIT-' + Date.now();
  const userId = marker;
  const name = marker + ' User';
  const email = marker.toLowerCase() + '@example.com';

  const login = await page.request.post('/api/auth/manual-login', {
    data: { employeeId: userId, userName: 'Git E2E User' },
  });
  expect(login.ok()).toBeTruthy();

  await page.goto('/git-settings.html');
  await expect(page.getByText('每用户 Git 提交身份与凭证')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText(userId)).toBeVisible();

  await page.getByPlaceholder('git config user.name，例如：周锐').fill(name);
  await page.getByPlaceholder('git config user.email，例如：zhourui@example.com').fill(email);
  await page.getByPlaceholder('GitLab 用户名').fill(marker + '-gitlab');
  await page.getByPlaceholder('GitLab 密码').fill('secret-' + marker);
  await page.getByRole('button', { name: '保存' }).click();
  await expect(page.locator('.el-message--success').filter({ hasText: '已保存' }))
    .toBeVisible({ timeout: 5_000 });

  await page.reload();
  await expect(page.getByPlaceholder('git config user.name，例如：周锐')).toHaveValue(name, { timeout: 10_000 });
  await expect(page.getByPlaceholder('git config user.email，例如：zhourui@example.com')).toHaveValue(email);
  await expect(page.getByPlaceholder('已配置（••••），留空保持不变')).toBeVisible();

  const api = await page.request.get('/api/user/git-config');
  expect(api.ok()).toBeTruthy();
  const body = await api.json();
  expect(body).toMatchObject({
    name,
    email,
    readOnly: false,
    credentialConfigured: true,
  });
  expect(body).not.toHaveProperty('credPassword');
});
