import { test, expect } from '@playwright/test';
import { loginAdminUI } from './_admin';

test('召回历史页面: route mock 渲染库存/丢弃列表并支持删除后空态', async ({ page }) => {
  const marker = 'E2E-RAG-' + Date.now();
  let chunks = [{
    id: 'chunk-' + marker,
    title: marker + ' 高分结论',
    conclusion: 'ServiceA 限流经验',
    status: 'ACTIVE',
    ttlCategory: 'LONG',
    score: 0.93,
    agentType: 'CODEX',
    sourceSessionId: '',
    createdAt: '2026-06-14T00:00:00Z',
  }];
  let discarded = [{
    id: 'discard-' + marker,
    title: marker + ' 低分记录',
    conclusion: '低分丢弃',
    status: 'DISCARDED',
    threshold: 0.8,
    score: 0.32,
    agentType: 'CLAUDE',
    sourceSessionId: '',
    createdAt: '2026-06-14T00:00:00Z',
  }];

  await page.route('**/api/refinery/chunks?**', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: chunks, total: chunks.length }) });
  });
  await page.route('**/api/refinery/discarded?**', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: discarded, total: discarded.length }) });
  });
  await page.route('**/api/refinery/chunks/*', async (route) => {
    chunks = [];
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true }) });
  });
  await page.route('**/api/refinery/discarded/*', async (route) => {
    discarded = [];
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true }) });
  });

  await loginAdminUI(page);
  await page.goto('/admin/refinery.html');
  await expect(page.getByRole('menuitem', { name: '召回历史' })).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText(marker + ' 高分结论')).toBeVisible({ timeout: 10_000 });
  await expect(page.locator('tbody').getByText('可召回')).toBeVisible();

  await page.getByRole('button', { name: '删除' }).first().click();
  await page.locator('.el-message-box').getByRole('button', { name: '删除' }).click();
  await expect(page.locator('.el-message--success').filter({ hasText: '已删除' }))
    .toBeVisible({ timeout: 5_000 });
  await expect(page.getByText('暂无召回记录')).toBeVisible({ timeout: 10_000 });

  await page.getByText('已丢弃(低分)', { exact: true }).click();
  await expect(page.getByText(marker + ' 低分记录')).toBeVisible({ timeout: 10_000 });
  await expect(page.locator('tbody').getByText('已丢弃')).toBeVisible();
});
