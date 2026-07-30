import { test, expect, type Page } from '@playwright/test';
import { gotoAdminMenu } from './_admin';

async function mockStatistics(page: Page): Promise<void> {
  await page.route('**/api/admin-tool-invocation-statistics/overview?*', route => {
    const query = new URL(route.request().url()).searchParams;
    const filtered = query.get('provider') === 'CODEX';
    return route.fulfill({ json: {
      invocationCount: filtered ? 0 : 10, conversationCount: filtered ? 0 : 2,
      averageInvocationsPerConversation: filtered ? null : 5,
      succeededCount: filtered ? 0 : 8, failedCount: filtered ? 0 : 1,
      incompleteCount: filtered ? 0 : 1, successRate: filtered ? null : .8,
      failureRate: filtered ? null : .1, incompleteRate: filtered ? null : .1,
      historyMigrationCount: filtered ? 0 : 10, liveCount: 0,
    } });
  });
  await page.route('**/api/admin-tool-invocation-statistics/daily-trend?*', route => route.fulfill({ json: [
    { date: '2026-07-28', invocationCount: 10, failedCount: 1, incompleteCount: 1 },
  ] }));
  await page.route('**/api/admin-tool-invocation-statistics/rankings?*', route => {
    const query = new URL(route.request().url()).searchParams;
    const rows: Record<string, unknown[]> = {
      TOOL: [{ analysisName: 'Bash', invocationCount: 6, conversationCount: 2, succeededCount: 5,
        failedCount: 1, terminalCount: 6, failureRate: 1 / 6, outputTruncatedCount: 0 }],
      COMMAND: [{ analysisName: 'git', invocationCount: 4, conversationCount: 2, succeededCount: 4,
        failedCount: 0, terminalCount: 4, failureRate: 0, outputTruncatedCount: 0 }],
      SKILL: [{ analysisName: 'review', invocationCount: 2, conversationCount: 1, succeededCount: 2,
        failedCount: 0, terminalCount: 2, failureRate: 0, outputTruncatedCount: 0 }],
    };
    return route.fulfill({ json: { items: rows[query.get('type') || 'TOOL'] } });
  });
  await page.route('**/api/admin-tool-invocation-statistics/conversations?*', route => {
    const filtered = new URL(route.request().url()).searchParams.get('provider') === 'CODEX';
    return route.fulfill({ json: { items: filtered ? [] : [
      { sessionId: 'session-1', title: '工具分析测试', userName: 'Admin', agentType: 'CLAUDE',
        invocationCount: 10, failedCount: 1, terminalCount: 10, failureRate: .1 },
    ] } });
  });
  await page.route('**/api/admin-tool-invocations?*', route => {
    const query = new URL(route.request().url()).searchParams;
    const filtered = query.get('provider') === 'CODEX';
    return route.fulfill({ json: { items: filtered ? [] : [
      { id: 7, startedAt: 1_753_660_800_000, provider: 'CLAUDE', displayToolName: 'Bash', outputSummary: 'failed' },
    ] } });
  });
  await page.route('**/api/admin-tool-invocations/7', route => route.fulfill({ json: {
    id: 7, provider: 'CLAUDE', invocationKind: 'TOOL_USE', status: 'FAILED', source: 'HISTORY_MIGRATION',
    displayToolName: 'Bash', inputJson: '{}', outputText: 'failed',
  } }));
}

test.beforeEach(async ({ page }) => mockStatistics(page));

test('工具分析支持筛选、排行 Tab、下钻和调用详情', async ({ page }) => {
  await gotoAdminMenu(page, '工具分析');
  await expect(page.locator('[data-test="tool-statistics-overview"]')).toContainText('10');

  await page.getByRole('tab', { name: '命令类别' }).click();
  await expect(page.locator('[data-test="tool-ranking-table"]')).toContainText('git');
  await page.getByRole('tab', { name: 'Skill' }).click();
  await expect(page.locator('[data-test="tool-ranking-table"]')).toContainText('review');
  await page.getByRole('tab', { name: '工具', exact: true }).click();
  await page.locator('[data-test="tool-ranking-table"]').getByRole('button', { name: '查看明细' }).click();
  await expect(page).toHaveURL(/analysisName=Bash/);

  const filter = page.locator('[data-test="tool-statistics-filter"]');
  await filter.locator('[data-test="provider-filter"]').click();
  await page.getByRole('option', { name: 'Codex' }).click();
  await filter.getByRole('button', { name: '应用' }).click();
  await expect(page).toHaveURL(/provider=CODEX/);
  await expect(page.locator('[data-test="tool-statistics-overview"]')).toContainText('调用总数0');
  await expect(page.locator('[data-test="tool-conversation-ranking"]')).toContainText('暂无数据');

  await filter.getByRole('button', { name: '重置' }).click();
  await expect(page.locator('[data-test="tool-failure-table"]')).toContainText('Bash');
  await page.locator('[data-test="tool-failure-table"]').getByRole('button', { name: '详情' }).click();
  await expect(page.getByRole('heading', { name: '工具调用详情' })).toBeVisible();
  await expect(page.getByText('HISTORY_MIGRATION')).toBeVisible();
});

test('工具分析恢复 URL 筛选并可跳转指定对话', async ({ page }) => {
  await page.goto('/admin/tool-invocation-analytics.html?provider=CODEX&source=LIVE');
  const filter = page.locator('[data-test="tool-statistics-filter"]');
  await expect(filter.locator('[data-test="provider-filter"]')).toContainText('Codex');
  await expect(filter.locator('[data-test="source-filter"]')).toContainText('实时记录');
  await expect(page.locator('[data-test="tool-statistics-overview"]')).toContainText('调用总数0');

  await filter.getByRole('button', { name: '重置' }).click();
  await page.route('**/api/metrics/conversations?*', route => route.fulfill({ json: { rows: [], total: 0 } }));
  await page.route('**/api/metrics/conversations/session-1', route => route.fulfill({ json: {
    record: { sessionId: 'session-1', title: '工具分析测试', userName: 'Admin', userId: 'admin',
      clientIp: '127.0.0.1', agentType: 'CLAUDE', createdAt: '2026-07-28T00:00:00Z', messageCount: 0 },
    messages: [],
  } }));
  await page.locator('[data-test="tool-conversation-ranking"]').getByRole('button', { name: '查看对话' }).click();
  await expect(page).toHaveURL(/\/admin\/conversations\.html\?sessionId=session-1/);
  await expect(page.getByRole('heading', { name: '工具分析测试' })).toBeVisible();
});
