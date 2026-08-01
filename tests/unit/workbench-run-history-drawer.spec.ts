/**
 * Workbench 历史 Run 抽屉的可达性、分页恢复与能力追溯展示契约。
 *
 * @author alex
 * @since 2026-08-01
 */
import { readFile } from 'node:fs/promises';
import { describe, expect, it } from 'vitest';

async function source(relativePath: string): Promise<string> {
  return readFile(new URL(`../../${relativePath}`, import.meta.url), 'utf8');
}

describe('WorkbenchRunHistoryDrawer source contract', () => {
  it('is reachable from the phase toolbar and renders historical timeline plus frozen capability', async () => {
    const [page, drawer] = await Promise.all([
      source('frontend/js/pages/Workbench.vue'),
      source('frontend/js/components/WorkbenchRunHistoryDrawer.vue'),
    ]);

    expect(page).toContain('data-test="open-run-history"');
    expect(page).toContain('<workbench-run-history-drawer');
    expect(drawer).toContain('data-test="workbench-run-history-list"');
    expect(drawer).toContain('data-test="workbench-run-history-timeline"');
    expect(drawer).toContain('data-test="workbench-run-history-capability"');
    expect(drawer).toContain('loadMoreEvents');
    expect(drawer).toContain('capability.bindingHash');
    expect(drawer).not.toContain('v-html');
  });
});
