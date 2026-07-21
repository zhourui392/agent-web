import { test, expect } from '@playwright/test';
import { gotoAdminMenu } from './_admin';

/**
 * 经验回填菜单 admin UI 渲染围栏(MPA 拆分前置基线)。
 *
 * e2e profile 关定时回填(agent.issue-log.backfill.enabled=false)且无候选 seed,
 * 故只锁住「菜单渲染候选区 + 操作按钮 + 空态」这一前端接线 —— MPA 拆分后
 * backfill.html 必须维持。深层审核弹窗行为由 backfill-api.spec.ts(API 契约)+ 手动覆盖,
 * 因审核需真实候选,e2e 关回填无法稳定 seed。
 */

test('admin 经验回填: 菜单渲染工具栏(按钮 + 说明)', async ({ page }) => {
  await gotoAdminMenu(page, '经验回填');

  // 工具栏稳定元素,与候选数据无关:手动回填按钮 + 说明文案。
  // 候选列表(卡片 / 空态)data-dependent(持久 e2e DB + backfill-api 的 /run 可能产候选),
  // 不在此断言;深层审核行为由 backfill-api.spec + 手动覆盖。
  await expect(page.getByRole('button', { name: '立即回填一轮' })).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText('从历史诊断挖经验')).toBeVisible({ timeout: 10_000 });
});
