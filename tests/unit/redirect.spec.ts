import { describe, it, expect } from 'vitest';
// @ts-ignore - JS 源码模块
import { sanitizeRedirect } from '../../frontend/js/lib/redirect.js';

/**
 * 承接原 base.spec.ts 中 sanitizeRedirect 的用例。挂载前缀(/qa)废弃后 base 参数取消,
 * 但 open-redirect 防御与 /api/ 回退这两条安全性质不变。
 */
describe('sanitizeRedirect', () => {
  it('站内页面路径原样返回(含 query)', () => {
    expect(sanitizeRedirect('/chat?env=prod')).toBe('/chat?env=prod');
    expect(sanitizeRedirect('/admin/dashboard.html')).toBe('/admin/dashboard.html');
  });

  it('/api/ 路径回退首页: 浏览器 GET 过去只会 405 或裸 JSON', () => {
    expect(sanitizeRedirect('/api/auth/logout')).toBe('/');
    expect(sanitizeRedirect('/api/chat/sessions')).toBe('/');
  });

  it('站外地址与协议相对 URL 一律回退首页(open-redirect 防御)', () => {
    expect(sanitizeRedirect('https://evil.com/x')).toBe('/');
    expect(sanitizeRedirect('//evil.com/x')).toBe('/');
  });

  it('空值与非 root-absolute 回退首页', () => {
    expect(sanitizeRedirect(null)).toBe('/');
    expect(sanitizeRedirect('')).toBe('/');
    expect(sanitizeRedirect('chat')).toBe('/');
  });

  it('前缀形似 /api 但非 /api/ 的路径不误判', () => {
    expect(sanitizeRedirect('/apidocs/index.html')).toBe('/apidocs/index.html');
  });
});
