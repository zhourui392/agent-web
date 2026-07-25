import { test, expect } from '@playwright/test';
import { loginAdminUI } from './_admin';

/**
 * Element Plus 改按需注册后的运行时兜底: 逐页确认没有「组件解析不到」。
 *
 * 为什么需要它: 漏注册时 Vue 不抛异常, 只在 dev 构建打一条 warn, 生产构建下
 * 标签原样留在 DOM 里静默不渲染。tests/unit/element-plus-registration.spec.ts 是
 * 构建期静态断言(模板 el-* ⊆ 注册清单), 但证明不了运行时真的解析成功 --
 * 比如按字符串名动态解析的 <component :is>。这里在真实浏览器里验收。
 *
 * 覆盖全部 12 个页面, 与各页自身业务 spec 是否通过无关 -- 部分页面的业务 spec
 * 因既有数据/状态机问题本就失败, 渲染层面反而没人看。
 */
// 实际存在的 admin 页面 (src/main/frontend/admin/*.html)。
// 注意别照抄 _admin.ts 的 MENU_SLUG: 那里还留着 diagnose/tickets/backfill 三个
// 已被删掉的页面(0c9378a 起 404), 照抄会得到查不出问题的假失败。
// harness.html 用了 207 处 el-*, 是按需注册风险最高的一页, 必须在列。
const ADMIN_PAGES = [
  'dashboard', 'conversations', 'workflows', 'recall',
  'refinery', 'chat', 'harness', 'settings', 'users',
];

/** 未被解析的自定义组件会原样留在 DOM: 查还剩多少 el-* / 图标标签没被替换掉。 */
async function unresolvedTags(page: import('@playwright/test').Page): Promise<string[]> {
  return page.evaluate(() => {
    const bad: string[] = [];
    for (const el of Array.from(document.querySelectorAll('*'))) {
      const tag = el.tagName.toLowerCase();
      // Element Plus 组件渲染后不会留下 el-xxx 标签名(会变成 div/button/input 等,
      // 带 .el-xxx class)。仍以 el- 开头的标签名 = 没解析成功。
      if (tag.startsWith('el-')) bad.push(tag);
    }
    return [...new Set(bad)];
  });
}

test.describe('Element Plus 按需注册: 逐页无未解析组件', () => {
  for (const slug of ADMIN_PAGES) {
    test(`admin/${slug}.html 组件全部解析`, async ({ page }) => {
      const warnings: string[] = [];
      page.on('console', (m) => {
        const t = m.text();
        if (/Failed to resolve component|resolve directive|is not a function/i.test(t)) {
          warnings.push(t);
        }
      });

      await loginAdminUI(page);
      await page.goto(`/admin/${slug}.html`);
      // 等 Vue 挂载完成: admin 壳的侧栏菜单渲染出来即说明根应用已 mount
      await expect(page.getByRole('menuitem', { name: '大盘' })).toBeVisible({ timeout: 15_000 });

      const unresolved = await unresolvedTags(page);
      expect(unresolved, `未解析组件: ${unresolved.join(', ')}`).toEqual([]);
      expect(warnings, `控制台解析告警:\n${warnings.join('\n')}`).toEqual([]);
    });
  }

  test('index.html (主聊天页) 组件全部解析, 含动态图标 :is', async ({ page }) => {
    const warnings: string[] = [];
    page.on('console', (m) => {
      const t = m.text();
      if (/Failed to resolve component|resolve directive/i.test(t)) warnings.push(t);
    });

    await page.goto('/');
    await expect(page.locator('textarea[placeholder*="输入你的问题"]'))
      .toBeEnabled({ timeout: 15_000 });

    const unresolved = await unresolvedTags(page);
    expect(unresolved, `未解析组件: ${unresolved.join(', ')}`).toEqual([]);
    expect(warnings, `控制台解析告警:\n${warnings.join('\n')}`).toEqual([]);
  });

  test('git-settings.html 组件全部解析', async ({ page }) => {
    await page.goto('/git-settings.html');
    await expect(page.getByText('每用户 Git 提交身份与凭证')).toBeVisible({ timeout: 15_000 });
    expect(await unresolvedTags(page)).toEqual([]);
  });
});
