import { test, expect } from '@playwright/test';

/**
 * 分享视图 E2E:
 * 1. 主页发 hello + 等 Codex stub 输出
 * 2. 截获 SSE 请求 URL 拿 sessionId (URL 里带 sessionId, 比 sessions 列表更稳)
 * 3. POST /api/chat/session/{sid}/share 拿 token (ShareController 生成 16 位)
 * 4. page.goto('/share.html?token=...') → 分享页渲染
 * 5. 断言 header "可续聊" tag (0e509bc 起分享页支持续聊), user/assistant 消息正确渲染
 *
 * 关键依赖:
 * - 走 chat 主链路落库, 不调底层 repo
 * - share.html 是独立静态页, 拉 GET /api/share/{token} 返回 messages
 */

const SHARE_MARKER = 'SHARE-' + Date.now();

test('share: 生成 token → /share.html 分享页渲染', async ({ page, request }) => {
  // ===== 1. UI: 发一条消息走 Codex stub =====
  await page.goto('/');
  const input = page.locator('textarea[placeholder*="输入你的问题"]');
  await expect(input).toBeEnabled({ timeout: 10_000 });

  // 监听 SSE 请求, 从 URL 抓 sessionId (路径形如 /api/chat/session/<sid>/message/stream)
  const streamReqPromise = page.waitForRequest(req =>
    /\/api\/chat\/session\/[^/]+\/message\/stream/.test(req.url())
  );

  await input.fill(SHARE_MARKER + ' hello');
  await page.getByRole('button', { name: '发送' }).click();

  const streamReq = await streamReqPromise;
  const m = streamReq.url().match(/\/api\/chat\/session\/([^/]+)\/message\/stream/);
  expect(m, 'sessionId in stream URL').toBeTruthy();
  const sid = decodeURIComponent(m![1]);

  // 等 Codex stub 输出回到 agent 气泡
  await expect(page.locator('.message-agent .text-segment').last())
    .toContainText('诊断结论', { timeout: 15_000 });
  // 流结束: 停止按钮消失
  await expect(page.getByRole('button', { name: '停止' })).toHaveCount(0, { timeout: 10_000 });

  // ===== 3. POST /api/chat/session/{sid}/share 拿 token =====
  const shareRes = await request.post(`/api/chat/session/${encodeURIComponent(sid)}/share`);
  expect(shareRes.ok()).toBeTruthy();
  const { shareToken } = await shareRes.json();
  expect(shareToken, 'shareToken in response').toBeTruthy();
  expect(shareToken.length).toBeGreaterThanOrEqual(8);

  // ===== 4. 在同一 page 跳转到 /share.html?token=... =====
  await page.goto(`/share.html?token=${encodeURIComponent(shareToken)}`);

  // ===== 5. 断言分享页渲染 =====
  // "可续聊" tag (header 里硬编码, 0e509bc 分享页续聊)
  await expect(page.getByText('可续聊', { exact: true })).toBeVisible({ timeout: 10_000 });

  // user 消息含 marker
  await expect(page.locator('.message-user-text').filter({ hasText: SHARE_MARKER }))
    .toBeVisible({ timeout: 5_000 });

  // assistant 消息是 Codex stub 输出
  await expect(page.locator('.message-agent .text-segment').filter({ hasText: '诊断结论' }))
    .toBeVisible({ timeout: 5_000 });
});
