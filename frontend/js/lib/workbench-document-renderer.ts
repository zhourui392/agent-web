/**
 * Workbench Document Viewer 的安全 Markdown 与有界文本展示模型。
 *
 * <p>Markdown 只在 marked 输出经过 DOMPurify 白名单净化后才返回 HTML；
 * 无 DOM、净化器不可用或任一步骤异常时，调用方必须使用 Vue 文本插值展示 source。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
import DOMPurify from 'dompurify';
import { marked } from 'marked';
import type { WorkbenchDocumentKind } from '../api/workbench-document.js';

const TEXT_KINDS = new Set<WorkbenchDocumentKind>([
  'SOURCE_CODE',
  'STRUCTURED_TEXT',
  'PLAIN_TEXT',
  'LOG_OR_REPORT',
]);

const SAFE_MARKDOWN_TAGS = [
  'a',
  'blockquote',
  'br',
  'code',
  'del',
  'em',
  'h1',
  'h2',
  'h3',
  'h4',
  'h5',
  'h6',
  'hr',
  'li',
  'ol',
  'p',
  'pre',
  'strong',
  'table',
  'tbody',
  'td',
  'th',
  'thead',
  'tr',
  'ul',
] as const;

const SAFE_MARKDOWN_ATTRIBUTES = [
  'href',
  'rel',
  'target',
  'title',
] as const;

const EXTERNAL_LINK = /^(?:https?:)?\/\//i;
const SAFE_LINK = /^(?:(?:https?:)?\/\/|mailto:|tel:|#)/i;
const SAFE_INLINE_IMAGE_OBJECT_URL = /^blob:\S+$/;
const SAFE_INLINE_IMAGE_MEDIA_TYPES = new Set([
  'image/png',
  'image/jpeg',
  'image/gif',
  'image/webp',
]);

const LANGUAGE_BY_EXTENSION: Readonly<Record<string, string>> = {
  c: 'C',
  cc: 'C++',
  cpp: 'C++',
  css: 'CSS',
  go: 'Go',
  h: 'C/C++ Header',
  hpp: 'C++ Header',
  html: 'HTML',
  java: 'Java',
  js: 'JavaScript',
  json: 'JSON',
  jsx: 'JSX',
  kt: 'Kotlin',
  kts: 'Kotlin',
  log: 'Log',
  md: 'Markdown',
  markdown: 'Markdown',
  mjs: 'JavaScript',
  py: 'Python',
  sh: 'Shell',
  sql: 'SQL',
  ts: 'TypeScript',
  tsx: 'TSX',
  txt: 'Text',
  vue: 'Vue',
  xml: 'XML',
  yaml: 'YAML',
  yml: 'YAML',
};

export const WORKBENCH_DOCUMENT_RENDER_LIMITS = {
  maximumRenderedLines: 10_000,
} as const;

export type WorkbenchDocumentDisplayMode =
  | 'MARKDOWN'
  | 'TEXT'
  | 'IMAGE'
  | 'BINARY'
  | 'UNSUPPORTED'
  | 'METADATA';

export interface WorkbenchTextLine {
  number: number;
  text: string;
}

export interface WorkbenchTextPresentation {
  lines: WorkbenchTextLine[];
  totalLines: number;
  omittedLineCount: number;
}

export interface WorkbenchMarkdownRenderResult {
  mode: 'SANITIZED_HTML' | 'PLAIN_TEXT';
  html: string | null;
  source: string;
}

export function createWorkbenchTextPresentation(
  content: string,
): WorkbenchTextPresentation {
  const source = typeof content === 'string' ? content : '';
  const lines: WorkbenchTextLine[] = [];
  let lineStart = 0;
  let totalLines = 0;
  for (let index = 0; index <= source.length; index++) {
    if (index !== source.length && source.charCodeAt(index) !== 10) continue;
    totalLines++;
    if (lines.length < WORKBENCH_DOCUMENT_RENDER_LIMITS.maximumRenderedLines) {
      lines.push({
        number: totalLines,
        text: source.slice(lineStart, index),
      });
    }
    lineStart = index + 1;
  }
  return {
    lines,
    totalLines,
    omittedLineCount: totalLines - lines.length,
  };
}

export function workbenchDocumentDisplayMode(
  kind: WorkbenchDocumentKind,
  content: string | null,
): WorkbenchDocumentDisplayMode {
  if (kind === 'MARKDOWN' && content !== null) return 'MARKDOWN';
  if (TEXT_KINDS.has(kind) && content !== null) return 'TEXT';
  if (kind === 'IMAGE') return 'IMAGE';
  if (kind === 'BINARY_METADATA') return 'BINARY';
  if (kind === 'UNSUPPORTED') return 'UNSUPPORTED';
  return 'METADATA';
}

export function workbenchDocumentLanguageLabel(
  relativePath: string,
  mediaType: string,
): string {
  const path = typeof relativePath === 'string' ? relativePath : '';
  const fileName = path.slice(path.lastIndexOf('/') + 1);
  const extensionIndex = fileName.lastIndexOf('.');
  if (extensionIndex >= 0 && extensionIndex < fileName.length - 1) {
    const extension = fileName.slice(extensionIndex + 1).toLowerCase();
    const label = LANGUAGE_BY_EXTENSION[extension];
    if (label) return label;
  }
  const normalizedMediaType = typeof mediaType === 'string'
    ? mediaType.toLowerCase()
    : '';
  if (normalizedMediaType.includes('json')) return 'JSON';
  if (normalizedMediaType.includes('yaml')) return 'YAML';
  if (normalizedMediaType.includes('xml')) return 'XML';
  if (normalizedMediaType.startsWith('text/')) return 'Text';
  return 'Document';
}

/**
 * Inline 图片只能展示由 scoped API 响应生成的 Blob URL；拒绝服务端未开放的 SVG 以及
 * data/javascript/普通文件系统地址。调用方仍需负责 URL.createObjectURL 的生命周期。
 */
export function workbenchInlineImagePreviewSource(
  source: unknown,
  kind: WorkbenchDocumentKind,
  mediaType: unknown,
): string | null {
  if (kind !== 'IMAGE'
    || typeof mediaType !== 'string'
    || !SAFE_INLINE_IMAGE_MEDIA_TYPES.has(mediaType.toLowerCase())
    || typeof source !== 'string'
    || !SAFE_INLINE_IMAGE_OBJECT_URL.test(source)) {
    return null;
  }
  return source;
}

export function renderWorkbenchMarkdown(source: string): WorkbenchMarkdownRenderResult {
  const markdown = typeof source === 'string' ? source : '';
  if (!markdownSanitizerAvailable()) return plainMarkdown(markdown);
  try {
    const rendered = marked.parse(markdown, {
      async: false,
      breaks: false,
      gfm: true,
    }) as string;
    const sanitized = DOMPurify.sanitize(rendered, {
      ALLOWED_ATTR: [...SAFE_MARKDOWN_ATTRIBUTES],
      ALLOWED_TAGS: [...SAFE_MARKDOWN_TAGS],
      ALLOW_ARIA_ATTR: false,
      ALLOW_DATA_ATTR: false,
      ALLOW_UNKNOWN_PROTOCOLS: false,
    });
    const template = document.createElement('template');
    template.innerHTML = sanitized;
    hardenMarkdownLinks(template.content);
    return {
      mode: 'SANITIZED_HTML',
      html: template.innerHTML,
      source: markdown,
    };
  } catch {
    return plainMarkdown(markdown);
  }
}

function markdownSanitizerAvailable(): boolean {
  return typeof document !== 'undefined'
    && DOMPurify != null
    && DOMPurify.isSupported === true
    && typeof DOMPurify.sanitize === 'function';
}

function hardenMarkdownLinks(root: DocumentFragment): void {
  for (const link of root.querySelectorAll('a')) {
    const href = link.getAttribute('href');
    if (!href) continue;
    if (!SAFE_LINK.test(href)) {
      link.removeAttribute('href');
      link.removeAttribute('rel');
      link.removeAttribute('target');
      continue;
    }
    link.setAttribute('rel', 'noopener noreferrer');
    if (EXTERNAL_LINK.test(href)) {
      link.setAttribute('target', '_blank');
    } else {
      link.removeAttribute('target');
    }
  }
}

function plainMarkdown(source: string): WorkbenchMarkdownRenderResult {
  return {
    mode: 'PLAIN_TEXT',
    html: null,
    source,
  };
}
