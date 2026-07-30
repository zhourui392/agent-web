import { expect, Page, test } from '@playwright/test';

const INPUT = 'textarea[placeholder*="输入你的问题"]';

const offer = (
  type: string,
  displayName: string,
  available: boolean,
  defaultEligible: boolean,
) => ({
  type,
  displayName,
  purpose: type === 'NATIVE' ? 'DIAGNOSIS' : 'GENERAL',
  available,
  userSelectable: true,
  defaultEligible,
  allEnvironments: type !== 'NATIVE',
  supportedEnvironments: type === 'NATIVE' ? ['test'] : [],
});

async function mockCatalog(page: Page, nativeAvailable: boolean): Promise<void> {
  await page.route('**/api/chat/agents', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      defaultAgent: 'CODEX',
      defaultVersion: 101,
      agents: [
        offer('CODEX', 'Codex', true, true),
        offer('CLAUDE', 'Claude', true, true),
        offer('NATIVE', '诊断 Agent', nativeAvailable, false),
      ],
    }),
  }));
  await page.route('**/api/chat/runs/active', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: '[]',
  }));
}

async function mockEmptyHistory(page: Page): Promise<void> {
  await page.route('**/api/chat/sessions?*', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: '[]',
  }));
}

test('desktop: 选择诊断 Agent，创建请求携带 NATIVE/test，随后选择器锁定', async ({ page }) => {
  await mockCatalog(page, true);
  await mockEmptyHistory(page);
  await page.route('**/api/chat/session', route => route.fulfill({
    status: 201,
    contentType: 'application/json',
    body: JSON.stringify({
      sessionId: 'native-session-e2e',
      workingDir: '/workspace',
      agentType: 'NATIVE',
      env: 'test',
    }),
  }));

  await page.goto('/');
  const nativeRadio = page.getByRole('radio', { name: '诊断 Agent' });
  await expect(nativeRadio).toBeVisible({ timeout: 10_000 });
  await nativeRadio.locator('..').click();
  await expect(nativeRadio).toBeChecked();

  const createRequest = page.waitForRequest(request =>
    request.url().endsWith('/api/chat/session') && request.method() === 'POST');
  await page.locator(INPUT).fill('NATIVE-E2E');
  await page.getByRole('button', { name: '发送' }).click();

  expect((await createRequest).postDataJSON()).toMatchObject({
    agentType: 'NATIVE',
    env: 'test',
  });
  await expect(page.locator('.topbar .locked-radio-group')).toBeVisible();
  await expect(nativeRadio).toBeChecked();
});

test('mobile: 抽屉中可以选择诊断 Agent', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockCatalog(page, true);
  await mockEmptyHistory(page);

  await page.goto('/');
  await page.locator('.hamburger-btn').click();
  const nativeRadio = page.getByRole('radio', { name: '诊断 Agent' });
  await expect(nativeRadio).toBeVisible({ timeout: 10_000 });
  await nativeRadio.locator('..').click();
  await expect(nativeRadio).toBeChecked();
});

test('runtime disabled: 新会话隐藏 NATIVE，历史仍标记不可用并禁止发送', async ({ page }) => {
  await mockCatalog(page, false);
  await page.route('**/api/chat/sessions?*', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([{
      sessionId: 'native-history-e2e',
      title: '历史诊断会话',
      workingDir: '/workspace',
      agentType: 'NATIVE',
      env: 'test',
      messageCount: 2,
      createdAt: new Date().toISOString(),
    }]),
  }));
  await page.route('**/api/chat/session/native-history-e2e/messages', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: '[]',
  }));

  await page.goto('/');
  await expect(page.getByRole('radio', { name: '诊断 Agent' })).toHaveCount(0);
  const history = page.locator('.history-item').filter({ hasText: '历史诊断会话' });
  await expect(history).toContainText('诊断 Agent（当前不可用）', { timeout: 10_000 });
  await history.locator('[title="继续对话"]').click();

  await expect(page.getByText('诊断 Agent 当前不可用；历史仍可查看、分享、评价和回退，但暂时不能继续发送。'))
    .toBeVisible();
  await expect(page.locator(INPUT)).toBeDisabled();
});
