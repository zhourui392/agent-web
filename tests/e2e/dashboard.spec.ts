import { test, expect } from '@playwright/test';
import { loginAdminUI } from './_admin';

/**
 * 大盘菜单 admin UI 围栏(MPA 拆分前置基线)。
 *
 * 大盘是登录后默认菜单,checkStatus 登录即 loadAll(/api/metrics/overview + /trend)。
 * 指标接口对空库返回结构化零值(非 null),故无需 seed 数据即可渲染 KPI 卡 + 趋势区。
 * 本 spec 锁住「登录后大盘默认渲染」这一行为,MPA 拆分后 dashboard.html 必须维持。
 */

test('admin 大盘: 登录后默认渲染 KPI 卡片与趋势区', async ({ page }) => {
  await loginAdminUI(page);

  // KPI 卡片(kpis 计算属性在 overview 非空时产出 8 张)
  await expect(page.locator('.kpi-card').first()).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText('会话总量')).toBeVisible();
  await expect(page.getByText('诊断总量')).toBeVisible();

  // 趋势卡片 section-title「近 N 天趋势」
  await expect(page.getByText('天趋势')).toBeVisible();
});
