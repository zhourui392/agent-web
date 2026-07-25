import { describe, it, expect } from 'vitest';
import { deriveBase, makeWithBase, sanitizeRedirect } from '../../src/main/frontend/js/base.js';

describe('deriveBase', () => {
  it('absolute src under /qa yields /qa', () => {
    expect(deriveBase('https://agent.example.com/qa/js/base.js')).toBe('/qa');
  });

  it('root-domain absolute src yields empty', () => {
    expect(deriveBase('https://agent.example.com/js/base.js')).toBe('');
  });

  it('strips cache-busting query', () => {
    expect(deriveBase('https://host/qa/js/base.js?v=20260607')).toBe('/qa');
  });

  it('path-only src under /qa yields /qa', () => {
    expect(deriveBase('/qa/js/base.js')).toBe('/qa');
  });

  it('nested prefix is preserved verbatim', () => {
    expect(deriveBase('https://host/team/qa/js/base.js')).toBe('/team/qa');
  });

  // Vite 打包后 base.js 变 /assets/<name>-<hash>.js 共享 chunk, 原 /js/base.js marker 失效,
  // 必须回落到 /assets/ marker, 否则 /qa 子路径部署推导出空前缀 -> 所有 root-absolute 请求丢前缀。
  it('bundled asset chunk under /qa yields /qa', () => {
    expect(deriveBase('https://host/qa/assets/base-a1b2c3d4.js')).toBe('/qa');
    expect(deriveBase('/qa/assets/base-a1b2c3d4.js')).toBe('/qa');
  });

  it('bundled asset chunk on root domain yields empty', () => {
    expect(deriveBase('https://host/assets/base-a1b2c3d4.js')).toBe('');
  });

  it('bundled asset chunk keeps nested prefix and honors the last /assets/ segment', () => {
    expect(deriveBase('https://host/team/qa/assets/index-9f8e7d.js')).toBe('/team/qa');
    expect(deriveBase('https://host/assets/app/assets/base-a1b2.js')).toBe('/assets/app');
  });

  it('unbundled marker still wins over a prefix that contains /assets/', () => {
    expect(deriveBase('https://host/assets/app/js/base.js')).toBe('/assets/app');
  });

  it('empty or marker-less src yields empty', () => {
    expect(deriveBase('')).toBe('');
    expect(deriveBase('https://host/js/other.js')).toBe('');
  });
});

describe('makeWithBase', () => {
  const withBase = makeWithBase('/qa');

  it('prefixes a root-absolute api path', () => {
    expect(withBase('/api/auth/status')).toBe('/qa/api/auth/status');
  });

  it('is idempotent on already-prefixed paths', () => {
    expect(withBase('/qa/api/auth/status')).toBe('/qa/api/auth/status');
    expect(withBase('/qa')).toBe('/qa');
  });

  it('does not false-match a sibling prefix boundary', () => {
    // "/qabc" must NOT be treated as already-prefixed by "/qa"
    expect(withBase('/qabc/x')).toBe('/qa/qabc/x');
  });

  it('leaves relative, full-url and protocol-relative untouched', () => {
    expect(withBase('vendor/vue.js')).toBe('vendor/vue.js');
    expect(withBase('https://cdn/x.js')).toBe('https://cdn/x.js');
    expect(withBase('//cdn/x.js')).toBe('//cdn/x.js');
  });

  it('empty base is a pass-through (dedicated-domain case)', () => {
    const passthrough = makeWithBase('');
    expect(passthrough('/api/auth/status')).toBe('/api/auth/status');
  });
});

describe('sanitizeRedirect', () => {
  it('keeps a normal page path', () => {
    expect(sanitizeRedirect('/chat?env=prod', '')).toBe('/chat?env=prod');
    expect(sanitizeRedirect('/qa/chat', '/qa')).toBe('/qa/chat');
  });

  it('falls back to home for api paths (the logout 405 ErrorPage case)', () => {
    expect(sanitizeRedirect('/api/auth/logout', '')).toBe('/');
    // 带 /qa 挂载前缀的 API 路径同样要被识别
    expect(sanitizeRedirect('/qa/api/auth/logout', '/qa')).toBe('/');
  });

  it('does not strip a sibling prefix boundary', () => {
    // base=/qa 不能把 /qabc/... 当成带前缀路径误剥
    expect(sanitizeRedirect('/qabc/page', '/qa')).toBe('/qabc/page');
  });

  it('falls back to home for missing, relative, absolute-url and protocol-relative values', () => {
    expect(sanitizeRedirect(null, '')).toBe('/');
    expect(sanitizeRedirect('', '')).toBe('/');
    expect(sanitizeRedirect('chat', '')).toBe('/');
    expect(sanitizeRedirect('https://evil.com/x', '')).toBe('/');
    expect(sanitizeRedirect('//evil.com/x', '')).toBe('/');
  });

  it('redirect equal to the bare prefix means home', () => {
    expect(sanitizeRedirect('/qa', '/qa')).toBe('/qa');
  });
});
