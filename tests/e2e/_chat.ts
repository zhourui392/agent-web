import { expect, Page } from '@playwright/test';

export const CHAT_INPUT = 'textarea[placeholder*="输入你的问题"]';
export const ASSISTANT_STUB_TEXT = '诊断结论';

export async function gotoChatReady(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page.locator(CHAT_INPUT)).toBeEnabled({ timeout: 10_000 });
}

export async function sendAndWaitEcho(page: Page, text: string): Promise<void> {
  const input = page.locator(CHAT_INPUT);
  await expect(input).toBeEnabled({ timeout: 10_000 });
  await input.fill(text);
  await page.getByRole('button', { name: '发送' }).click();
  await expect(page.locator('.message-agent .text-segment').last())
    .toContainText(ASSISTANT_STUB_TEXT, { timeout: 15_000 });
  await expect(page.getByRole('button', { name: '停止' })).toHaveCount(0, { timeout: 10_000 });
}
