import { Page, expect } from '@playwright/test';

/**
 * Admin UI e2e 共享登录 / 导航 helper。
 *
 * 管理后台 MPA(每菜单一页):/admin 入口 302 跳 /admin/dashboard.html,各菜单是
 * /admin/<slug>.html 真实静态页,点侧栏菜单 = 整页跳转。鉴权复用数据库用户会话，
 * 并要求 ADMIN 角色。
 */

const MENU_SLUG: Record<string, string> = {
  '大盘': 'dashboard',
  '对话记录': 'conversations',
  '诊断历史': 'diagnose',
  'IM 工单': 'tickets',
  '经验回填': 'backfill',
  '用户建议': 'suggestions',
  '工作流': 'workflows',
  '召回观测': 'recall',
  '召回历史': 'refinery',
  '对话': 'chat',
};

/** /admin 入口复用 global setup 已建立的 ADMIN 会话。 */
export async function loginAdminUI(page: Page): Promise<void> {
  await page.goto('/admin');
  const dashboardMenu = page.getByRole('menuitem', { name: '大盘' });
  if (await dashboardMenu.isVisible({ timeout: 2_000 }).catch(() => false)) {
    return;
  }
  throw new Error('ADMIN session is missing; run Playwright with AGENT_E2E_ADMIN_PASSWORD');
}

/** 登录后点侧栏菜单 → 整页跳到 /admin/<slug>.html,等导航完成 + 新页菜单再现(cookie 复用鉴权)。 */
export async function gotoAdminMenu(page: Page, menuName: string): Promise<void> {
  await loginAdminUI(page);
  const slug = MENU_SLUG[menuName];
  // 菜单可能带动态角标(如「经验回填 4」),accessible name 不再等于菜单文案,
  // 不能用 { name, exact: true };改成「菜单项内含精确文本节点」——角标是兄弟节点不影响,
  // 「对话」也不会误配「对话记录」(整段文本节点不相等)。
  const menuItem = adminMenuItem(page, menuName);
  await Promise.all([
    page.waitForURL(new RegExp('/admin/' + slug + '\\.html')),
    menuItem.click(),
  ]);
  await expect(adminMenuItem(page, menuName)).toBeVisible({ timeout: 10_000 });
}

function adminMenuItem(page: Page, menuName: string) {
  return page.getByRole('menuitem').filter({ has: page.getByText(menuName, { exact: true }) });
}
