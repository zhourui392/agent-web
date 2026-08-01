/**
 * Workbench Document Renderer 的 fail-closed 与有界文本展示契约。
 *
 * @author alex
 * @since 2026-08-01
 */
import { readFile } from 'node:fs/promises';
import { describe, expect, it } from 'vitest';
import {
  WORKBENCH_DOCUMENT_RENDER_LIMITS,
  createWorkbenchTextPresentation,
  renderWorkbenchMarkdown,
  workbenchDocumentDisplayMode,
  workbenchInlineImagePreviewSource,
  workbenchDocumentLanguageLabel,
} from '../../frontend/js/lib/workbench-document-renderer.js';

describe('workbench document text presentation', () => {
  it('numbers every line, including a trailing empty line, without interpreting markup', () => {
    const presentation = createWorkbenchTextPresentation('const html = "<script>";\n');

    expect(presentation).toEqual({
      lines: [
        { number: 1, text: 'const html = "<script>";' },
        { number: 2, text: '' },
      ],
      totalLines: 2,
      omittedLineCount: 0,
    });
  });

  it('bounds DOM line creation for newline-heavy content', () => {
    const content = Array.from(
      { length: WORKBENCH_DOCUMENT_RENDER_LIMITS.maximumRenderedLines + 3 },
      (_, index) => `line-${index + 1}`,
    ).join('\n');

    const presentation = createWorkbenchTextPresentation(content);

    expect(presentation.lines).toHaveLength(
      WORKBENCH_DOCUMENT_RENDER_LIMITS.maximumRenderedLines,
    );
    expect(presentation.totalLines).toBe(
      WORKBENCH_DOCUMENT_RENDER_LIMITS.maximumRenderedLines + 3,
    );
    expect(presentation.omittedLineCount).toBe(3);
  });

  it('maps supported text and metadata kinds to explicit render modes', () => {
    expect(workbenchDocumentDisplayMode('MARKDOWN', '# docs')).toBe('MARKDOWN');
    expect(workbenchDocumentDisplayMode('SOURCE_CODE', 'class App {}')).toBe('TEXT');
    expect(workbenchDocumentDisplayMode('STRUCTURED_TEXT', '{}')).toBe('TEXT');
    expect(workbenchDocumentDisplayMode('PLAIN_TEXT', '')).toBe('TEXT');
    expect(workbenchDocumentDisplayMode('LOG_OR_REPORT', 'PASS')).toBe('TEXT');
    expect(workbenchDocumentDisplayMode('IMAGE', 'untrusted-image-body')).toBe('IMAGE');
    expect(workbenchDocumentDisplayMode('BINARY_METADATA', null)).toBe('BINARY');
    expect(workbenchDocumentDisplayMode('UNSUPPORTED', null)).toBe('UNSUPPORTED');
    expect(workbenchDocumentDisplayMode('SOURCE_CODE', null)).toBe('METADATA');
  });

  it('labels known source and structured formats for the registered highlighter', () => {
    expect(workbenchDocumentLanguageLabel('src/App.java', 'text/x-java-source')).toBe('Java');
    expect(workbenchDocumentLanguageLabel('js/App.vue', 'text/plain')).toBe('Vue');
    expect(workbenchDocumentLanguageLabel('config/data.json', 'application/json')).toBe('JSON');
    expect(workbenchDocumentLanguageLabel('reports/test.log', 'text/plain')).toBe('Log');
    expect(workbenchDocumentLanguageLabel('README', 'text/plain')).toBe('Text');
  });
});

describe('workbench markdown fail-closed rendering', () => {
  it('returns plain source when DOMPurify is unavailable in a non-DOM runtime', () => {
    const source = '# title\n<script>alert(1)</script>';

    const rendered = renderWorkbenchMarkdown(source);

    expect(rendered).toEqual({
      mode: 'PLAIN_TEXT',
      html: null,
      source,
    });
  });
});

describe('workbench scoped image rendering', () => {
  it('accepts only an object URL produced from an allowlisted raster image response', () => {
    expect(workbenchInlineImagePreviewSource(
      'blob:https://agent.example/preview-id',
      'IMAGE',
      'image/png',
    )).toBe('blob:https://agent.example/preview-id');

    for (const source of [
      'data:image/png;base64,AAAA',
      'javascript:alert(1)',
      '/api/fs/image?path=%2Fetc%2Fpasswd',
      '/absolute/private/diagram.png',
    ]) {
      expect(workbenchInlineImagePreviewSource(source, 'IMAGE', 'image/png')).toBeNull();
    }
    expect(workbenchInlineImagePreviewSource(
      'blob:https://agent.example/svg-id',
      'IMAGE',
      'image/svg+xml',
    )).toBeNull();
    expect(workbenchInlineImagePreviewSource(
      'blob:https://agent.example/not-image',
      'PLAIN_TEXT',
      'image/png',
    )).toBeNull();
  });
});

describe('WorkbenchDocumentPane render contract', () => {
  it('uses only sanitized markdown HTML and text interpolation for document bodies', async () => {
    const component = await readFile(new URL(
      '../../frontend/js/components/WorkbenchDocumentPane.vue',
      import.meta.url,
    ), 'utf8');

    expect(component).toContain('v-html="markdownRender.html"');
    expect(component.match(/v-html=/g)).toHaveLength(2);
    expect(component).not.toMatch(/v-html="(?:loadedContent|currentDocument)/);
    expect(component).toContain('v-html="line.highlightedHtml"');
    expect(component).toContain('v-else role="cell">{{ line.text }}');
    expect(component).toContain('data-test="workbench-document-line-number"');
    expect(component).toContain(':src="inlineImageSource"');
    expect(component).not.toMatch(/:src="(?:loadedContent|currentDocument)/);
    expect(component).not.toMatch(/data:image|javascript:/i);
  });

  it('has explicit loading, error, stale, deleted, image, binary and unsupported states', async () => {
    const component = await readFile(new URL(
      '../../frontend/js/components/WorkbenchDocumentPane.vue',
      import.meta.url,
    ), 'utf8');

    for (const state of [
      'workbench-document-loading',
      'workbench-document-error',
      'workbench-document-stale',
      'workbench-document-deleted',
      'workbench-document-image',
      'workbench-document-binary',
      'workbench-document-unsupported',
    ]) {
      expect(component).toContain(`data-test="${state}"`);
    }
    expect(component).not.toMatch(/\/api\/fs/);
  });
});
