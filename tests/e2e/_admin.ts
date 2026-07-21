import { Page, expect } from '@playwright/test';

/**
 * Admin UI e2e 共享登录 / 导航 helper。
 *
 * 管理后台 MPA(每菜单一页):/admin 入口 302 跳 /admin/dashboard.html,各菜单是
 * /admin/<slug>.html 真实静态页,点侧栏菜单 = 整页跳转。鉴权走管理口令 cookie(admin_session),
 * 跨页导航后 cookie 复用,壳重新 checkStatus 即认得。口令固定为 application-e2e.yml 的 agent.admin.password。
 */

export const ADMIN_PASSWORD = 'e2e-admin-pass';

const MENU_SLUG: Record<string, string> = {
  '大盘': 'dashboard',
  '对话记录': 'conversations',
  '诊断历史': 'diagnose',
  'IM 工单': 'tickets',
  '经验回填': 'backfill',
  '用户建议': 'suggestions',
  '工作流': 'workflows',
  '需求事件': 'requirement-events',
  '召回观测': 'recall',
  '召回历史': 'refinery',
  '对话': 'chat',
};

/** /admin 入口(302 → dashboard)填管理口令登录,登录后侧栏「大盘」菜单出现即返回。 */
export async function loginAdminUI(page: Page): Promise<void> {
  await page.goto('/admin');
  const dashboardMenu = page.getByRole('menuitem', { name: '大盘' });
  if (await dashboardMenu.isVisible({ timeout: 2_000 }).catch(() => false)) {
    return;
  }
  await page.getByPlaceholder('请输入管理口令').fill(ADMIN_PASSWORD);
  await page.getByRole('button', { name: '登录' }).click();
  await expect(dashboardMenu).toBeVisible({ timeout: 10_000 });
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
