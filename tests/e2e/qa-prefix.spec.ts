import { test, expect } from '@playwright/test';

test('/qa 前缀: 首页静态资源、chat API、admin 登录与 share 页面路径可用', async ({ page, baseURL }) => {
  const marker = 'E2E-QA-' + Date.now();
  const withQaBase = (path: string) => new URL(path, (baseURL || '') + '/').toString();

  await page.goto('/login.html');
  await page.getByPlaceholder('请输入用户名').fill('admin');
  await page.getByPlaceholder('请输入密码').fill(process.env.AGENT_E2E_ADMIN_PASSWORD || '');
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page).toHaveURL(/\/qa\/?$/);
  const input = page.locator('textarea[placeholder*="输入你的问题"]');
  await expect(input).toBeEnabled({ timeout: 10_000 });
  await input.fill(marker + ' hello');
  await page.getByRole('button', { name: '发送' }).click();
  await expect(page.locator('.message-agent .text-segment').last())
    .toContainText('诊断结论', { timeout: 15_000 });

  const sessions = await page.context().request.get(withQaBase('api/chat/sessions?page=1&size=20'));
  expect(sessions.ok()).toBeTruthy();
  const session = (await sessions.json()).find((s: { title?: string; sessionId: string }) => (s.title || '').includes(marker));
  expect(session).toBeTruthy();

  const share = await page.context().request.post(
    withQaBase('api/chat/session/' + encodeURIComponent(session.sessionId) + '/share')
  );
  expect(share.ok()).toBeTruthy();
  const shareBody = await share.json();
  await page.goto(withQaBase('share.html?token=' + shareBody.shareToken));
  await expect(page).toHaveURL(/\/qa\/share\.html/);
  await expect(page.getByText(marker).first()).toBeVisible({ timeout: 10_000 });

  await page.goto(withQaBase('admin'));
  await expect(page).toHaveURL(/\/qa\/admin/);
  await expect(page.getByRole('menuitem', { name: '大盘' })).toBeVisible({ timeout: 10_000 });

  expect(new URL(baseURL || '').pathname).toBe('/qa');
});
