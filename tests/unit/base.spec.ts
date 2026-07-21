import { describe, it, expect } from 'vitest';
import { createRequire } from 'node:module';

const requireCjs = createRequire(import.meta.url);
const base = requireCjs('../../src/main/resources/static/js/base.js') as {
  deriveBase: (scriptSrc: string) => string;
  makeWithBase: (base: string) => (path: string) => string;
  sanitizeRedirect: (redirect: string | null, base: string) => string;
};

describe('deriveBase', () => {
  it('absolute src under /qa yields /qa', () => {
    expect(base.deriveBase('https://agent.example.com/qa/js/base.js')).toBe('/qa');
  });

  it('root-domain absolute src yields empty', () => {
    expect(base.deriveBase('https://agent.example.com/js/base.js')).toBe('');
  });

  it('strips cache-busting query', () => {
    expect(base.deriveBase('https://host/qa/js/base.js?v=20260607')).toBe('/qa');
  });

  it('path-only src under /qa yields /qa', () => {
    expect(base.deriveBase('/qa/js/base.js')).toBe('/qa');
  });

  it('nested prefix is preserved verbatim', () => {
    expect(base.deriveBase('https://host/team/qa/js/base.js')).toBe('/team/qa');
  });

  it('empty or marker-less src yields empty', () => {
    expect(base.deriveBase('')).toBe('');
    expect(base.deriveBase('https://host/js/other.js')).toBe('');
  });
});

describe('makeWithBase', () => {
  const withBase = base.makeWithBase('/qa');

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
    const passthrough = base.makeWithBase('');
    expect(passthrough('/api/auth/status')).toBe('/api/auth/status');
  });
});

describe('sanitizeRedirect', () => {
  it('keeps a normal page path', () => {
    expect(base.sanitizeRedirect('/chat?env=prod', '')).toBe('/chat?env=prod');
    expect(base.sanitizeRedirect('/qa/chat', '/qa')).toBe('/qa/chat');
  });

  it('falls back to home for api paths (the logout 405 ErrorPage case)', () => {
    expect(base.sanitizeRedirect('/api/auth/logout', '')).toBe('/');
    // 带 /qa 挂载前缀的 API 路径同样要被识别
    expect(base.sanitizeRedirect('/qa/api/auth/logout', '/qa')).toBe('/');
  });

  it('does not strip a sibling prefix boundary', () => {
    // base=/qa 不能把 /qabc/... 当成带前缀路径误剥
    expect(base.sanitizeRedirect('/qabc/page', '/qa')).toBe('/qabc/page');
  });

  it('falls back to home for missing, relative, absolute-url and protocol-relative values', () => {
    expect(base.sanitizeRedirect(null, '')).toBe('/');
    expect(base.sanitizeRedirect('', '')).toBe('/');
    expect(base.sanitizeRedirect('chat', '')).toBe('/');
    expect(base.sanitizeRedirect('https://evil.com/x', '')).toBe('/');
    expect(base.sanitizeRedirect('//evil.com/x', '')).toBe('/');
  });

  it('redirect equal to the bare prefix means home', () => {
    expect(base.sanitizeRedirect('/qa', '/qa')).toBe('/qa');
  });
});
