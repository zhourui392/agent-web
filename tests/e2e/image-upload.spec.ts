import { test, expect } from '@playwright/test';
import * as fs from 'fs/promises';
import * as os from 'os';
import * as path from 'path';

/**
 * 图片上传 / 删除链路 E2E:
 * 1. 准备本地 1px PNG 临时文件
 * 2. 通过 el-upload 的 <input type=file> 注入 → 触发 uploadChatImage → POST /api/fs/upload-image
 * 3. 后端 UploadPicStore 写到 <workingDir>/upload_pic/<sessionId>/, 前端 pendingImages push 一项
 * 4. 缩略图卡片渲染 → 点 × 删除 → 卡片消失 (仅前端移除, 服务端文件保留)
 *
 * 关键依赖:
 * - e2e profile fs.roots 含 ${user.dir}, currentPath 默认填 roots[0], 上传路径合法
 * - el-upload 隐藏 <input type=file>, Playwright 用 setInputFiles 直接灌字节
 * - 1×1 PNG 最小合法字节, 走 FsController 校验 (魔数 + 1MB 上限)
 */

// 1x1 transparent PNG, 67 bytes (满足 magic-number 校验 + 远低于 1MB 上限)
const ONE_PX_PNG = Buffer.from([
  0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49,
  0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06,
  0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4, 0x89, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x44,
  0x41, 0x54, 0x78, 0x9c, 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01, 0x0d,
  0x0a, 0x2d, 0xb4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42,
  0x60, 0x82,
]);

let tmpPngPath: string;

test.beforeAll(async () => {
  const dir = path.join(os.tmpdir(), 'e2e-image-upload-' + Date.now());
  await fs.mkdir(dir, { recursive: true });
  tmpPngPath = path.join(dir, '1px.png');
  await fs.writeFile(tmpPngPath, ONE_PX_PNG);
});

test.afterAll(async () => {
  if (tmpPngPath) {
    await fs.rm(path.dirname(tmpPngPath), { recursive: true, force: true }).catch(() => {
      // afterAll 清理失败不影响测试结论
    });
  }
});

test('图片上传 → 缩略图渲染 → 点 × 删除', async ({ page }) => {
  await page.goto('/');
  // textarea 可用 = init 完成 (currentPath 已自动取 roots[0])
  await expect(page.locator('textarea[placeholder*="输入你的问题"]'))
    .toBeEnabled({ timeout: 10_000 });

  // el-upload 内嵌的 input[type=file]: 输入框走 accept="image/*", 用这个 filter 锁定图片 input,
  // 避开附件 input (accept=".log,.txt,...")
  const imageInput = page.locator('input[type=file][accept="image/*"]');
  await imageInput.setInputFiles(tmpPngPath);

  // 后端上传完, success toast 出现
  await expect(page.locator('.el-message--success').filter({ hasText: '图片已上传' }))
    .toBeVisible({ timeout: 10_000 });

  // 缩略图卡片渲染
  const card = page.locator('.pending-image-card');
  await expect(card).toHaveCount(1, { timeout: 5_000 });

  // 计数器同步: "图片 (1/4)"
  await expect(page.getByText(/图片\s*\(1\/4\)/)).toBeVisible();

  // 点 × 删除
  await card.first().locator('.pending-image-remove').click();

  // 卡片消失, 计数器归零
  await expect(card).toHaveCount(0, { timeout: 3_000 });
  await expect(page.getByText(/图片\s*\(0\/4\)/)).toBeVisible();
});
