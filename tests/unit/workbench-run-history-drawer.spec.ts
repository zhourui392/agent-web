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
  it('is reachable from the Stage toolbar and renders historical timeline plus frozen capability', async () => {
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

  it('renders the frozen Repository Scope with relative identity, primary marker, and exact access only', async () => {
    const drawer = await source('frontend/js/components/WorkbenchRunHistoryDrawer.vue');

    expect(drawer).toContain('data-test="workbench-run-repository-scope"');
    expect(drawer).toContain('v-for="repository in capability.repositories"');
    expect(drawer).toContain('{{ repository.repositoryKey }}');
    expect(drawer).toContain('{{ repository.relativePath }}');
    expect(drawer).toContain("repository.primary ? '主仓' : '参与仓'");
    expect(drawer).toContain('{{ repository.access }}');

    const scopeStart = drawer.indexOf('data-test="workbench-run-repository-scope"');
    const scopeEnd = drawer.indexOf('</section>', scopeStart);
    const scope = drawer.slice(scopeStart, scopeEnd);
    expect(scope).not.toMatch(/absolutePath|repositoryRoot|workspaceRoot|workingDir|command|env/i);
  });

  it('renders bounded shell command and output content while keeping details collapsed', async () => {
    const drawer = await source('frontend/js/components/WorkbenchRunHistoryDrawer.vue');

    expect(drawer).toContain('block.commandSummary || block.outputSummary');
    expect(drawer).toContain('data-test="workbench-history-command-content"');
    expect(drawer).toContain('{{ block.commandContent }}');
    expect(drawer).toContain('data-test="workbench-history-command-output"');
    expect(drawer).toContain('{{ block.outputContent }}');
    expect(drawer).not.toContain('<details open');
  });
});
