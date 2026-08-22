/**
 * 管理后台模式管理页的可达性、入口切换与能力目录契约。
 *
 * @author zhourui
 * @since 2026/08/22
 */
import { readFile } from 'node:fs/promises';
import { describe, expect, it } from 'vitest';

async function source(relativePath: string): Promise<string> {
  return readFile(new URL(`../../${relativePath}`, import.meta.url), 'utf8');
}

describe('Admin modes page', () => {
  it('is a dedicated AdminShell MPA reachable from the admin navigation', async () => {
    const [html, entry, shell, page] = await Promise.all([
      source('frontend/admin/modes.html'),
      source('frontend/js/admin/pages/modes.js'),
      source('frontend/js/admin/AdminShell.vue'),
      source('frontend/js/admin/pages/Modes.vue'),
    ]);

    expect(html).toContain('/js/admin/pages/modes.js');
    expect(entry).toContain("import Page from './Modes.vue'");
    expect(shell).toContain('index="modes"');
    expect(page).toContain('<admin-shell active="modes"');
  });

  it('manages all users modes and browses the capability catalog read-only', async () => {
    const [page, api] = await Promise.all([
      source('frontend/js/admin/pages/Modes.vue'),
      source('frontend/js/api/mode.ts'),
    ]);

    expect(api).toContain("'/api/admin-settings/modes'");
    expect(page).toContain('listAllModes');
    expect(page).toContain('listCapabilityCatalog');
    expect(page).toContain('ElMessageBox.confirm');
    expect(page).toContain('forkMode');
    expect(page).toContain('/admin/capabilities.html');
    expect(page).not.toContain('v-html');
  });

  it('routes the main-console manage entry to the admin page with no standalone page left', async () => {
    const app = await source('frontend/js/App.vue');

    expect(app).toContain("window.location.href = '/admin/modes.html'");
    expect(app).not.toContain("'/modes.html'");
    await expect(
      source('frontend/modes.html'),
    ).rejects.toThrow();
    await expect(
      source('frontend/js/pages/Modes.vue'),
    ).rejects.toThrow();
  });
});
