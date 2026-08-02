/**
 * 独立 Admin Workbench MPA 的可达性与权限收敛契约。
 *
 * @author alex
 * @since 2026-08-01
 */
import { readFile } from 'node:fs/promises';
import { describe, expect, it } from 'vitest';

async function source(relativePath: string): Promise<string> {
  return readFile(new URL(`../../${relativePath}`, import.meta.url), 'utf8');
}

describe('Admin Workbench page', () => {
  it('is a dedicated AdminShell MPA reachable from the admin navigation', async () => {
    const [html, entry, shell, page] = await Promise.all([
      source('frontend/admin/workbenches.html'),
      source('frontend/js/admin/pages/workbenches.js'),
      source('frontend/js/admin/AdminShell.vue'),
      source('frontend/js/admin/pages/Workbenches.vue'),
    ]);

    expect(html).toContain('/js/admin/pages/workbenches.js');
    expect(entry).toContain("import Page from './Workbenches.vue'");
    expect(shell).toContain('index="workbenches"');
    expect(page).toContain('<admin-shell active="workbenches"');
  });

  it('shows safe Workbench/Run facts and explicit Stop/Reconcile confirmations only', async () => {
    const page = await source('frontend/js/admin/pages/Workbenches.vue');

    expect(page).toContain('data-test="admin-workbench-list"');
    expect(page).toContain('data-test="admin-workbench-detail"');
    expect(page).toContain('data-test="admin-workbench-run-list"');
    expect(page).toContain('data-test="admin-workbench-stop"');
    expect(page).toContain('data-test="admin-workbench-reconcile"');
    expect(page).toContain('ElMessageBox.confirm');
    expect(page).toContain('不会以创建者身份提交消息或批准高影响操作');
    expect(page).toContain('不会重放 Provider');
    expect(page).not.toContain('v-html');
  });

  it('has no Owner execution, Phase message, Handoff, Override, Review or Operation entry', async () => {
    const [page, api] = await Promise.all([
      source('frontend/js/admin/pages/Workbenches.vue'),
      source('frontend/js/admin/api/workbench.ts'),
    ]);
    const template = page.slice(0, page.indexOf('<script setup'));

    expect(template).not.toMatch(/发送消息|提交 Phase|Handoff|能力覆盖|Review Confirmation|批准操作|执行操作/);
    expect(api).not.toMatch(/OwnerReference|handoff|override|confirmation|operation/i);
    expect(api).toContain("body: '{}'");
  });

  it('never renders physical paths, sessions, prompt bodies, raw output, stderr or secrets', async () => {
    const [page, api] = await Promise.all([
      source('frontend/js/admin/pages/Workbenches.vue'),
      source('frontend/js/admin/api/workbench.ts'),
    ]);
    const forbidden = /workspaceRoot|repositoryRoot|rootFingerprint|sessionId|promptBody|errorMessage|toolOutput|stderr|secret/i;

    expect(page).not.toMatch(forbidden);
    expect(api).not.toMatch(/workspaceRoot|repositoryRoot|rootFingerprint|sessionId|promptBody|errorMessage|toolOutput|stderr/);
  });
});
