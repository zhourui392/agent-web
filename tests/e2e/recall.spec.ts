import { test, expect } from '@playwright/test';
import { gotoAdminMenu } from './_admin';

/**
 * Admin recall-observability menu smoke tests.
 *
 * They do not depend on seeded recall data; they only pin the menu entry,
 * filters, KPI cards, and basic list/detail wiring.
 */
test('admin 召回观测: 菜单渲染筛选区和指标列表', async ({ page }) => {
  await gotoAdminMenu(page, '召回观测');

  await expect(page.getByPlaceholder('sessionId')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByPlaceholder('embeddingModel')).toBeVisible();
  await expect(page.getByRole('button', { name: '查询' })).toBeVisible();
  // "质量命中率"/"召回尝试" 同时出现在指标口径说明面板里, 用 KPI 卡片作用域避免严格模式双匹配
  await expect(page.locator('.kpi-label').filter({ hasText: '质量命中率' }))
    .toBeVisible({ timeout: 10_000 });
  await expect(page.locator('.kpi-label').filter({ hasText: '召回尝试' })).toBeVisible();
  await expect(page.locator('.el-table').first()).toBeVisible();
});

test('admin 召回观测: 展示分桶质量、score 样本和 Top chunks', async ({ page }) => {
  await page.route(/\/api\/metrics\/recall(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        attemptCount: 3,
        executedCount: 3,
        hitCount: 1,
        noHitCount: 1,
        errorCount: 1,
        qualityHitRate: 0.5,
        noHitRate: 0.5,
        errorRate: 1 / 3,
        serviceAvailabilityRate: 2 / 3,
        avgHitCount: 1,
        avgLatencyMs: 123,
        byStatus: { HIT: 1, NO_HIT: 1, ERROR: 1 },
        envBuckets: [
          { key: 'test', hitCount: 1, noHitCount: 1, errorCount: 1, qualityHitRate: 0.5 },
        ],
        embeddingModelBuckets: [
          { key: 'qwen', hitCount: 1, noHitCount: 1, errorCount: 0, qualityHitRate: 0.5 },
        ],
        sourceTypeBuckets: [
          { key: 'CHAT', hitCount: 1, noHitCount: 0, errorCount: 0, qualityHitRate: 1 },
        ],
        tierBuckets: [
          { key: 'EXPLORATORY', hitCount: 1, noHitCount: 0, errorCount: 0, qualityHitRate: 1 },
        ],
        scoreSamples: [
          {
            attemptId: 'att-nohit',
            status: 'NO_HIT',
            topVectorScore: 0.594,
            topFinalScore: null,
            belowVectorFloor: 8,
            filteredCount: 8,
            badVectorCount: 0,
            rankedCount: 0,
            createdAt: 1710000000000,
          },
        ],
      }),
    });
  });
  await page.route(/\/api\/metrics\/recall-attempts(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        page: 1,
        size: 20,
        total: 1,
        items: [
          {
            id: 'att-hit',
            sessionId: 'sess-1',
            querySummary: '如何排查召回',
            status: 'HIT',
            hitCount: 1,
            topVectorScore: 0.91,
            topFinalScore: 0.88,
            belowVectorFloor: 0,
            rankedCount: 3,
            embeddingModel: 'qwen',
            env: 'test',
            latencyMs: 88,
            createdAt: 1710000000000,
          },
        ],
      }),
    });
  });
  await page.route(/\/api\/metrics\/recall-chunks(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          chunkId: 'chunk-1',
          recalledTimes: 2,
          avgVectorScore: 0.81,
          avgFinalScore: 0.78,
          title: '退款历史结论',
          sourceType: 'CHAT',
          tier: 'EXPLORATORY',
          lastRecalledAt: 1710000000000,
        },
      ]),
    });
  });

  await gotoAdminMenu(page, '召回观测');

  // "质量分桶" 同时出现在指标口径说明面板里, 用面板标题作用域避免严格模式双匹配
  await expect(page.locator('.section-title').filter({ hasText: '质量分桶' }))
    .toBeVisible({ timeout: 10_000 });
  await expect(page.getByText('env · test')).toBeVisible();
  await expect(page.getByText('model · qwen')).toBeVisible();
  await expect(page.getByText('source · CHAT')).toBeVisible();
  await expect(page.getByText('tier · EXPLORATORY')).toBeVisible();
  await expect(page.locator('.section-title').filter({ hasText: 'Score 样本' })).toBeVisible();
  await expect(page.getByText('att-nohit')).toBeVisible();
  await expect(page.getByText('topVector 0.594')).toBeVisible();
  await expect(page.locator('.section-title').filter({ hasText: 'Top chunks' })).toBeVisible();
  await expect(page.getByText('chunk-1')).toBeVisible();
  await expect(page.getByText('召回 2 次')).toBeVisible();
});
