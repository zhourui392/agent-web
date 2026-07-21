import { test, expect } from '@playwright/test';
import { gotoAdminMenu } from './_admin';

/**
 * IM 工单菜单 admin UI 渲染围栏。
 */

test('admin IM 工单: 菜单渲染筛选栏和列表区域', async ({ page }) => {
  await gotoAdminMenu(page, 'IM 工单');

  await expect(page.getByText('飞书 IM 登记的诊断工单')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByPlaceholder('搜索工单 / taskId / 问题')).toBeVisible();
  await expect(page.getByRole('button', { name: '筛选' })).toBeVisible();
  await expect(page.getByText('暂无 IM 工单')).toBeVisible({ timeout: 10_000 });
});
