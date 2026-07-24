import { expect, test } from '@playwright/test';

test('Feature Flag 关闭时 Harness 导航和 API 都不出现', async ({ page, request }) => {
  const response = await request.get('/api/harness/runs/__feature_flag_probe__');
  expect(response.status()).toBe(404);
  const body = await response.json();
  expect(body.code).not.toBe('HARNESS_RUN_NOT_FOUND');

  await page.goto('/admin/dashboard.html');
  await expect(page.getByRole('menuitem', { name: 'Harness' })).toHaveCount(0);

  await page.goto('/admin/harness.html');
  await expect(page.getByText('Harness Feature Flag 当前关闭')).toBeVisible();
});
