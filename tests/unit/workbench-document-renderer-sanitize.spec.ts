// @vitest-environment jsdom
/**
 * Workbench Markdown 在真实 DOMPurify 环境中的白名单净化契约。
 *
 * @author alex
 * @since 2026-08-01
 */
import { describe, expect, it } from 'vitest';
import { renderWorkbenchMarkdown } from '../../frontend/js/lib/workbench-document-renderer.js';

describe('workbench markdown sanitizer', () => {
  it('renders ordinary markdown and hardens external links', () => {
    const rendered = renderWorkbenchMarkdown(
      '# Design\n\n**safe** [docs](https://example.test/design)',
    );

    expect(rendered.mode).toBe('SANITIZED_HTML');
    expect(rendered.html).toContain('<h1>Design</h1>');
    expect(rendered.html).toContain('<strong>safe</strong>');
    expect(rendered.html).toContain('href="https://example.test/design"');
    expect(rendered.html).toContain('rel="noopener noreferrer"');
    expect(rendered.html).toContain('target="_blank"');
  });

  it('removes executable HTML, embeds, style hooks and dangerous URIs', () => {
    const rendered = renderWorkbenchMarkdown(
      '<script>alert(1)</script>'
      + '<iframe src="https://evil.test"></iframe>'
      + '<p id="x" class="workbench-shell" style="display:none" onclick="alert(2)">text</p>'
      + '<a href="javascript:alert(3)">danger</a>',
    );

    expect(rendered.mode).toBe('SANITIZED_HTML');
    expect(rendered.html).not.toMatch(/script|iframe|onclick|javascript:|style=|class=|id=/i);
    expect(rendered.html).toContain('text');
    expect(rendered.html).toContain('danger');
  });

  it('blocks all Markdown image sources until a scoped inline endpoint is supplied', () => {
    const rendered = renderWorkbenchMarkdown(
      '![remote](https://evil.test/a.png)'
      + '<img src="data:image/png;base64,AAAA" onerror="alert(1)">',
    );

    expect(rendered.mode).toBe('SANITIZED_HTML');
    expect(rendered.html).not.toMatch(/<img|src=|evil\.test|data:image/i);
  });

  it('does not turn relative or same-origin API paths into links outside scoped resolution', () => {
    const rendered = renderWorkbenchMarkdown(
      '[ordinary fs](/api/fs/download?path=/etc/passwd) '
      + '[relative](../private.md) '
      + '[section](#safe-section)',
    );

    expect(rendered.mode).toBe('SANITIZED_HTML');
    expect(rendered.html).not.toContain('/api/fs');
    expect(rendered.html).not.toContain('../private.md');
    expect(rendered.html).toContain('href="#safe-section"');
  });
});
