// @vitest-environment jsdom
//
// renderMarkdown 在「有 DOM」环境下的真实行为: marked 渲染 + DOMPurify 净化。
// 这是浏览器里实际走的路径 -- formatters.spec.ts 跑 node 环境, DOMPurify.isSupported=false,
// 只能覆盖 fail-closed 转义分支, 覆盖不到净化本身。两个文件合起来才是完整的安全断言。
import { describe, it, expect } from 'vitest';
import { renderMarkdown } from '../../frontend/js/lib/formatters.js';

describe('renderMarkdown (jsdom: marked + DOMPurify 实际生效)', () => {
  it('renders markdown to HTML', () => {
    const out = renderMarkdown('**bold** and `code`');

    expect(out).toContain('<strong>bold</strong>');
    expect(out).toContain('<code>code</code>');
  });

  it('strips event handlers, javascript: URLs and script tags', () => {
    const out = renderMarkdown(
      '<img src="x" onerror="alert(1)">'
      + '<a href="javascript:alert(2)">click</a>'
      + '<script>alert(3)</script>'
    );

    expect(out).not.toContain('onerror');
    expect(out).not.toContain('javascript:');
    expect(out).not.toContain('<script');
    expect(out).toContain('click');
  });

  it('drops forbidden interactive and embedding tags', () => {
    const out = renderMarkdown(
      '<iframe src="//evil.test"></iframe>'
      + '<form><input name="x"><button>go</button></form>'
      + '<style>body{display:none}</style>'
    );

    expect(out).not.toContain('<iframe');
    expect(out).not.toContain('<form');
    expect(out).not.toContain('<input');
    expect(out).not.toContain('<button');
    expect(out).not.toContain('<style');
  });

  it('keeps safe links and images intact', () => {
    const out = renderMarkdown('[docs](https://example.test/a) ![img](https://example.test/i.png)');

    expect(out).toContain('href="https://example.test/a"');
    expect(out).toContain('src="https://example.test/i.png"');
  });

  it('still collapses 3+ newlines before rendering', () => {
    const out = renderMarkdown('a\n\n\n\nb');

    expect(out).toContain('a');
    expect(out).toContain('b');
    expect(out).not.toContain('\n\n\n');
  });
});
