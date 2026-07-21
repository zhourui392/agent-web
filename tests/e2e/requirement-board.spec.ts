import { test, expect } from '@playwright/test';

// M0 看板主链路(真实后端, 不 mock API): 建需求 → INTAKE 卡片 → 贴计划 → 审批 → 列迁移可见。
// e2e profile 已打开 agent.requirement.enabled(application-e2e.yml)。
test('需求看板主链路: 创建 → 贴计划 → 审批, 卡片随状态迁移换列', async ({ page }) => {
  const title = 'E2E需求-' + Date.now();

  await page.goto('/requirement-board.html');
  await expect(page.getByRole('button', { name: '新建需求' })).toBeVisible({ timeout: 10_000 });

  // 创建需求 → INTAKE 列出现卡片
  await page.getByRole('button', { name: '新建需求' }).click();
  await page.getByPlaceholder('需求标题').fill(title);
  await page.getByRole('button', { name: '创建' }).click();
  await expect(page.locator('.el-message--success').filter({ hasText: '需求已创建' }))
    .toBeVisible({ timeout: 5_000 });
  const intakeColumn = page.locator('.board-column[data-status="INTAKE"]');
  await expect(intakeColumn.getByText(title)).toBeVisible({ timeout: 5_000 });

  // 打开抽屉 → 计划 Tab 提交计划 → 卡片迁到 PLANNED 列
  await intakeColumn.getByText(title).click();
  await page.getByRole('tab', { name: '计划' }).click();
  await page.getByPlaceholder('粘贴或撰写实施计划').fill('1. 写测试 2. 实现 3. 重构');
  await page.getByRole('button', { name: '提交计划' }).click();
  await expect(page.locator('.el-message--success').filter({ hasText: '操作成功' }).first())
    .toBeVisible({ timeout: 5_000 });

  // 审批通过 → 卡片迁到 APPROVED 列
  await page.getByRole('button', { name: '审批通过' }).click();
  await expect(page.locator('.board-column[data-status="APPROVED"]').getByText(title))
    .toBeVisible({ timeout: 5_000 });

  // 事件时间线包含完整迁移审计
  await page.getByRole('tab', { name: '事件' }).click();
  await expect(page.getByRole('listitem').filter({ hasText: 'CREATED' })).toBeVisible();
  await expect(page.getByRole('listitem').filter({ hasText: 'INTAKE → PLANNED' })).toBeVisible();
  await expect(page.getByRole('listitem').filter({ hasText: 'PLANNED → APPROVED' })).toBeVisible();
});
