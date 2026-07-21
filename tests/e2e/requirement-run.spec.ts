import { test, expect } from '@playwright/test';
import { gotoAdminMenu } from './_admin';

// M2 计划门主链路(真实后端, claude stub = cmd /c echo, 输出前缀 "Echo "):
// 创建需求 → 「AI 生成计划」发 plan-run(202 异步) → 前端 3s 轮询详情, PLANNED 后自动刷新抽屉。
test('AI 生成计划: plan-run 异步完成后状态变 PLANNED 且计划正文非空', async ({ page }) => {
  const title = 'E2E计划RUN-' + Date.now();

  // 创建需求(沿用 M0 看板创建路径)
  await page.goto('/requirement-board.html');
  await expect(page.getByRole('button', { name: '新建需求' })).toBeVisible({ timeout: 10_000 });
  await page.getByRole('button', { name: '新建需求' }).click();
  await page.getByPlaceholder('需求标题').fill(title);
  await page.getByRole('button', { name: '创建' }).click();
  const intakeColumn = page.locator('.board-column[data-status="INTAKE"]');
  await expect(intakeColumn.getByText(title)).toBeVisible({ timeout: 5_000 });

  // 打开详情抽屉 → 发起 AI 生成计划
  await intakeColumn.getByText(title).click();
  await page.getByRole('button', { name: 'AI 生成计划' }).click();

  // 前端轮询(3s 间隔)直到 PLANNED 后刷新抽屉; 状态徽标限定在抽屉(dialog)内断言, 避免撞看板列标题「已计划」
  const drawer = page.getByRole('dialog');
  await expect(drawer.getByText('已计划')).toBeVisible({ timeout: 60_000 });

  // 计划 Tab: echo stub 把 prompt 原样回显, 正文以 "Echo" 开头(宽松断言)
  await page.getByRole('tab', { name: '计划' }).click();
  await expect(page.locator('.plan-text')).toContainText('Echo', { timeout: 10_000 });
});

// admin 需求事件页冒烟: 过滤条与表格容器可见, 点一次查询不报错(无数据也算通过)。
test('admin 需求事件页: 过滤条与表格可见, 查询不报错', async ({ page }) => {
  await gotoAdminMenu(page, '需求事件');

  await expect(page.getByPlaceholder('actor')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByPlaceholder('开始时间')).toBeVisible();
  const searchButton = page.getByRole('button', { name: '查询' });
  await expect(searchButton).toBeVisible();

  // 表头列渲染成功即表格容器可见
  await expect(page.getByText('事件类型')).toBeVisible();

  await searchButton.click();
  // 查询完成后无错误提示(空数据展示 empty-text, 不算错)
  await expect(page.locator('.el-message--error')).toHaveCount(0);
});
