import { test, expect } from '@playwright/test';

// /qa 子路径部署下的「打包产物能否自举」验证。
//
// 为什么单独一个 spec: 打包后 base.js 变成 /assets/<name>-<hash>.js 共享 chunk,
// 原先靠文件名 /js/base.js 推导前缀的逻辑会失效 -> deriveBase 退到 /assets/ marker。
// 这条链路只有在带 context-path 的真实部署下才走得到, 单测覆盖不到 asset URL 的真实形态。
//
// 注意: baseURL 是 http://localhost:18100/qa (无尾斜杠), 无论写 '/login.html' 还是
// 'login.html', new URL() 都会丢掉 /qa 段, 必须显式补尾斜杠再 join。
test('/qa 前缀: 打包产物自举, 前缀推导正确, 相对 asset 可加载', async ({ page, baseURL }) => {
  const withQaBase = (path: string) => new URL(path, (baseURL || '') + '/').toString();
  const failed: string[] = [];
  page.on('requestfailed', (r) => failed.push(`${r.url()} ${r.failure()?.errorText ?? ''}`));
  page.on('response', (r) => {
    if (r.status() >= 400) failed.push(`${r.url()} -> ${r.status()}`);
  });

  await page.goto(withQaBase('login.html'));

  // 登录页的 Vue 应用真的挂载了(说明 bundle 加载 + 运行时模板编译都成功)
  await expect(page.getByPlaceholder('请输入用户名')).toBeVisible({ timeout: 10_000 });

  // 前缀推导: base.js 从自身 /qa/assets/*.js 的 URL 反推出 "/qa"
  const appBase = await page.evaluate(() => (window as unknown as { __APP_BASE__?: string }).__APP_BASE__);
  expect(appBase).toBe('/qa');

  // withBase 幂等补前缀, 业务代码里的 root-absolute 路径不会丢 /qa
  const wrapped = await page.evaluate(
    () => (window as unknown as { withBase: (p: string) => string }).withBase('/api/auth/status')
  );
  expect(wrapped).toBe('/qa/api/auth/status');

  // 经包裹后的 fetch 打到带前缀的地址, 且真能拿到响应
  const status = await page.evaluate(async () => {
    const res = await fetch('/api/auth/status');
    return { ok: res.ok, url: new URL(res.url).pathname };
  });
  expect(status.url).toBe('/qa/api/auth/status');
  expect(status.ok).toBeTruthy();

  // 所有 JS/CSS chunk 都加载成功: 相对 base ('./assets/...') 在子路径下不会 404
  expect(failed, `失败请求:\n${failed.join('\n')}`).toEqual([]);
});
