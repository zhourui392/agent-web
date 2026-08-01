/**
 * Workbench Owner Scope 内的只读 Document API client。
 *
 * @author alex
 * @since 2026-08-01
 */

const CONTROL_CHARACTER = /[\u0000-\u001f\u007f]/;
const WINDOWS_ABSOLUTE_PATH = /^[A-Za-z]:[\\/]/;
const MAX_IDENTIFIER_CHARS = 4096;
const MAX_ETAG_CHARS = 1024;
const MAX_FILE_NAME_CHARS = 255;
const MAX_MEDIA_TYPE_CHARS = 255;
const MAX_ENCODING_CHARS = 64;
const MAX_CONTENT_VERSION_CHARS = 512;
const MAX_CONTENT_CHARS = 2 * 1024 * 1024;
const MAX_INLINE_IMAGE_BYTES = 10 * 1024 * 1024;
const MAX_CONTENT_DISPOSITION_CHARS = 4096;
const SAFE_ERROR_MESSAGE = 'Workbench document request failed';
const SAFE_RESPONSE_ERROR_CODES = new Set([
  'WORKBENCH_REQUEST_INVALID',
  'WORKBENCH_NOT_FOUND',
  'WORKBENCH_REPOSITORY_NOT_FOUND',
  'WORKBENCH_REPOSITORY_SCOPE_INVALID',
  'WORKBENCH_PATH_FORBIDDEN',
  'WORKBENCH_DOCUMENT_REQUEST_INVALID',
  'WORKBENCH_DOCUMENT_NOT_FOUND',
  'WORKBENCH_DOCUMENT_DELETED',
  'WORKBENCH_DOCUMENT_TOO_LARGE',
  'WORKBENCH_DOCUMENT_UNSUPPORTED',
  'WORKBENCH_DOCUMENT_CHANGED_DURING_READ',
  'WORKSPACE_PATH_FORBIDDEN',
  'WORKSPACE_TOPOLOGY_CHANGED',
]);
const DELETED_DOCUMENT_CODES = new Set([
  'WORKBENCH_DOCUMENT_NOT_FOUND',
  'WORKBENCH_DOCUMENT_DELETED',
]);
const DOCUMENT_KINDS = new Set<WorkbenchDocumentKind>([
  'MARKDOWN',
  'SOURCE_CODE',
  'STRUCTURED_TEXT',
  'PLAIN_TEXT',
  'LOG_OR_REPORT',
  'IMAGE',
  'BINARY_METADATA',
  'UNSUPPORTED',
]);
const INLINE_IMAGE_MEDIA_TYPES = new Set<WorkbenchInlineImageMediaType>([
  'image/png',
  'image/jpeg',
  'image/gif',
  'image/webp',
]);
const INLINE_IMAGE_ACCEPT = [...INLINE_IMAGE_MEDIA_TYPES].join(', ');

export const WORKBENCH_DOCUMENT_API_LIMITS = {
  maximumTreeEntries: 1000,
  maximumPathChars: 4096,
} as const;

export type WorkbenchDocumentFetch = (
  input: string,
  init?: RequestInit,
) => Promise<Response>;

export interface WorkbenchDocumentLocator {
  workbenchId: string;
  repositoryKey: string;
  relativePath: string;
}

export interface WorkbenchDocumentTreeRequest extends WorkbenchDocumentLocator {
  limit?: number;
}

export type WorkbenchDocumentTreeEntryKind = 'DIRECTORY' | 'FILE';

export interface WorkbenchDocumentTreeEntry {
  name: string;
  relativePath: string;
  kind: WorkbenchDocumentTreeEntryKind;
  size: number | null;
  lastModified: number;
}

export interface WorkbenchDocumentTreeView {
  repositoryKey: string;
  path: string;
  entries: WorkbenchDocumentTreeEntry[];
  truncated: boolean;
}

export type WorkbenchDocumentKind =
  | 'MARKDOWN'
  | 'SOURCE_CODE'
  | 'STRUCTURED_TEXT'
  | 'PLAIN_TEXT'
  | 'LOG_OR_REPORT'
  | 'IMAGE'
  | 'BINARY_METADATA'
  | 'UNSUPPORTED';

export type WorkbenchInlineImageMediaType =
  | 'image/png'
  | 'image/jpeg'
  | 'image/gif'
  | 'image/webp';

export interface WorkbenchDocumentContentView {
  reference: {
    repositoryKey: string;
    relativePath: string;
  };
  kind: WorkbenchDocumentKind;
  mediaType: string;
  encoding: string | null;
  size: number;
  lastModified: number;
  contentVersion: string;
  content: string | null;
  truncated: boolean;
  deleted: boolean;
}

export type WorkbenchDocumentContentResult =
  | {
    status: 'LOADED';
    etag: string | null;
    document: WorkbenchDocumentContentView;
  }
  | {
    status: 'NOT_MODIFIED';
    etag: string | null;
  }
  | {
    status: 'DELETED';
  };

export type WorkbenchDocumentInlineImageResult =
  | {
    status: 'LOADED';
    blob: Blob;
    mediaType: WorkbenchInlineImageMediaType;
    size: number;
    etag: string;
  }
  | {
    status: 'NOT_MODIFIED';
    etag: string | null;
  }
  | {
    status: 'DELETED';
  };

export interface WorkbenchDocumentDownload {
  blob: Blob;
  fileName: string;
  mediaType: string | null;
  size: number;
  etag: string | null;
}

export interface WorkbenchDocumentApiClient {
  listTree(request: WorkbenchDocumentTreeRequest): Promise<WorkbenchDocumentTreeView>;
  readContent(
    locator: WorkbenchDocumentLocator,
    ifNoneMatch?: string,
  ): Promise<WorkbenchDocumentContentResult>;
  readInlineImage(
    locator: WorkbenchDocumentLocator,
    ifNoneMatch?: string,
  ): Promise<WorkbenchDocumentInlineImageResult>;
  download(locator: WorkbenchDocumentLocator): Promise<WorkbenchDocumentDownload>;
}

export class WorkbenchDocumentApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
  ) {
    super(SAFE_ERROR_MESSAGE);
    this.name = 'WorkbenchDocumentApiError';
    const sanitized = this as Error & { cause?: unknown; body?: unknown };
    delete sanitized.stack;
    delete sanitized.cause;
    delete sanitized.body;
  }
}

export function createWorkbenchDocumentApiClient(
  injectedFetch?: WorkbenchDocumentFetch,
): WorkbenchDocumentApiClient {
  const execute: WorkbenchDocumentFetch = injectedFetch
    ?? ((input, init) => globalThis.fetch(input, init));

  return {
    async listTree(request: WorkbenchDocumentTreeRequest): Promise<WorkbenchDocumentTreeView> {
      const limit = requireTreeLimit(request.limit);
      const url = scopedDocumentUrl(request, 'tree', true, { limit });
      const response = await safeFetch(execute, url, jsonRequest());
      if (!response.ok) await throwSafeResponseError(response);
      return parseTreeResponse(await readJsonValue(response), request, limit, response.status);
    },

    async readContent(
      locator: WorkbenchDocumentLocator,
      ifNoneMatch?: string,
    ): Promise<WorkbenchDocumentContentResult> {
      const headers: Record<string, string> = { Accept: 'application/json' };
      if (ifNoneMatch != null) headers['If-None-Match'] = requireEtag(ifNoneMatch);
      const url = scopedDocumentUrl(locator, 'content', false);
      const response = await safeFetch(execute, url, {
        method: 'GET',
        credentials: 'same-origin',
        headers,
      });
      if (response.status === 304) {
        return { status: 'NOT_MODIFIED', etag: responseEtag(response) };
      }
      if (response.status === 404) {
        const error = await safeResponseError(response);
        if (DELETED_DOCUMENT_CODES.has(error.code)) return { status: 'DELETED' };
        throw error;
      }
      if (!response.ok) await throwSafeResponseError(response);
      return {
        status: 'LOADED',
        etag: responseEtag(response),
        document: parseContentResponse(
          await readJsonValue(response),
          locator,
          response.status,
        ),
      };
    },

    async readInlineImage(
      locator: WorkbenchDocumentLocator,
      ifNoneMatch?: string,
    ): Promise<WorkbenchDocumentInlineImageResult> {
      const headers: Record<string, string> = { Accept: INLINE_IMAGE_ACCEPT };
      if (ifNoneMatch != null) headers['If-None-Match'] = requireEtag(ifNoneMatch);
      const url = scopedDocumentUrl(locator, 'inline-image', false);
      const response = await safeFetch(execute, url, {
        method: 'GET',
        credentials: 'same-origin',
        headers,
      });
      if (response.status === 304) {
        return { status: 'NOT_MODIFIED', etag: responseEtag(response) };
      }
      if (response.status === 404) {
        const error = await safeResponseError(response);
        if (DELETED_DOCUMENT_CODES.has(error.code)) return { status: 'DELETED' };
        throw error;
      }
      if (!response.ok) await throwSafeResponseError(response);
      const responseMediaType = inlineImageMediaType(response.headers.get('Content-Type'));
      const etag = responseEtag(response);
      if (!responseMediaType || !etag) throw invalidResponse(response.status);
      const blob = await readBlob(response);
      const blobMediaType = inlineImageMediaType(blob.type);
      if (blob.size > MAX_INLINE_IMAGE_BYTES
        || !blobMediaType
        || blobMediaType !== responseMediaType) {
        throw invalidResponse(response.status);
      }
      return {
        status: 'LOADED',
        blob,
        mediaType: responseMediaType,
        size: blob.size,
        etag,
      };
    },

    async download(
      locator: WorkbenchDocumentLocator,
    ): Promise<WorkbenchDocumentDownload> {
      const url = scopedDocumentUrl(locator, 'download', false);
      const response = await safeFetch(execute, url, {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/octet-stream' },
      });
      if (!response.ok) await throwSafeResponseError(response);
      const blob = await readBlob(response);
      const headerFileName = contentDispositionFileName(
        response.headers.get('Content-Disposition'),
      );
      const requestedFileName = safeFileName(locator.relativePath);
      return {
        blob,
        fileName: headerFileName || requestedFileName || 'download',
        mediaType: mediaType(response.headers.get('Content-Type'), blob.type),
        size: blob.size,
        etag: responseEtag(response),
      };
    },
  };
}

function jsonRequest(): RequestInit {
  return {
    method: 'GET',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  };
}

function scopedDocumentUrl(
  locator: WorkbenchDocumentLocator,
  endpoint: 'tree' | 'content' | 'inline-image' | 'download',
  allowRoot: boolean,
  query: { limit?: number } = {},
): string {
  if (!locator) throw invalidRequest();
  const workbenchId = requireIdentifier(locator.workbenchId);
  const repositoryKey = requireRelativePath(locator.repositoryKey, false);
  const relativePath = requireRelativePath(locator.relativePath, allowRoot);
  const base = [
    '/api/workbenches',
    encodeURIComponent(workbenchId),
    'documents',
    endpoint,
  ].join('/');
  const parameters = [
    `repositoryKey=${encodeURIComponent(repositoryKey)}`,
    `path=${encodeURIComponent(relativePath)}`,
  ];
  if (query.limit != null) parameters.push(`limit=${query.limit}`);
  return `${base}?${parameters.join('&')}`;
}

function requireIdentifier(value: unknown): string {
  if (typeof value !== 'string'
    || value.length === 0
    || value.length > MAX_IDENTIFIER_CHARS
    || value === '.'
    || value === '..'
    || value.startsWith('/')
    || WINDOWS_ABSOLUTE_PATH.test(value)
    || value.includes('\\')
    || CONTROL_CHARACTER.test(value)) {
    throw invalidRequest();
  }
  return value;
}

function requireRelativePath(value: unknown, allowRoot: boolean): string {
  if (typeof value !== 'string'
    || value.length > WORKBENCH_DOCUMENT_API_LIMITS.maximumPathChars
    || !allowRoot && value.length === 0
    || value.startsWith('/')
    || WINDOWS_ABSOLUTE_PATH.test(value)
    || value.includes('\\')
    || CONTROL_CHARACTER.test(value)) {
    throw invalidRequest();
  }
  if (value.length === 0) return value;
  const segments = value.split('/');
  if (segments.some(segment => segment.length === 0 || segment === '.' || segment === '..')) {
    throw invalidRequest();
  }
  return value;
}

function requireTreeLimit(value: number | undefined): number {
  const limit = value ?? WORKBENCH_DOCUMENT_API_LIMITS.maximumTreeEntries;
  if (!Number.isInteger(limit)
    || limit < 1
    || limit > WORKBENCH_DOCUMENT_API_LIMITS.maximumTreeEntries) {
    throw invalidRequest();
  }
  return limit;
}

function requireEtag(value: string): string {
  if (typeof value !== 'string'
    || value.length === 0
    || value.length > MAX_ETAG_CHARS
    || CONTROL_CHARACTER.test(value)) {
    throw invalidRequest();
  }
  return value;
}

async function safeFetch(
  execute: WorkbenchDocumentFetch,
  url: string,
  init: RequestInit,
): Promise<Response> {
  try {
    return await execute(url, init);
  } catch {
    throw new WorkbenchDocumentApiError(0, 'WORKBENCH_DOCUMENT_NETWORK_ERROR');
  }
}

async function throwSafeResponseError(response: Response): Promise<never> {
  throw await safeResponseError(response);
}

async function safeResponseError(response: Response): Promise<WorkbenchDocumentApiError> {
  let code = 'WORKBENCH_DOCUMENT_REQUEST_FAILED';
  try {
    const text = await response.text();
    const body = text ? JSON.parse(text) as { code?: unknown } : null;
    if (body && typeof body.code === 'string' && SAFE_RESPONSE_ERROR_CODES.has(body.code)) {
      code = body.code;
    }
  } catch {
    // 错误响应中的原始文本和解析异常均不进入公开错误对象。
  }
  return new WorkbenchDocumentApiError(response.status, code);
}

async function readJsonValue(response: Response): Promise<unknown> {
  try {
    return await response.json() as unknown;
  } catch {
    throw new WorkbenchDocumentApiError(
      response.status,
      'WORKBENCH_DOCUMENT_RESPONSE_INVALID',
    );
  }
}

function parseTreeResponse(
  value: unknown,
  request: WorkbenchDocumentTreeRequest,
  limit: number,
  status: number,
): WorkbenchDocumentTreeView {
  const body = requireRecord(value, status);
  if (body.repositoryKey !== request.repositoryKey
    || body.path !== request.relativePath
    || !Array.isArray(body.entries)
    || body.entries.length > limit
    || typeof body.truncated !== 'boolean') {
    throw invalidResponse(status);
  }
  const entries = body.entries.map((entry) => parseTreeEntry(entry, status));
  return {
    repositoryKey: request.repositoryKey,
    path: request.relativePath,
    entries,
    truncated: body.truncated,
  };
}

function parseTreeEntry(value: unknown, status: number): WorkbenchDocumentTreeEntry {
  const entry = requireRecord(value, status);
  const name = boundedMetadata(entry.name, MAX_FILE_NAME_CHARS, false);
  const relativePath = safeRelativeResponsePath(entry.relativePath);
  const kind = entry.kind;
  const size = entry.size;
  const lastModified = entry.lastModified;
  if (!name
    || name === '.'
    || name === '..'
    || name.includes('/')
    || name.includes('\\')
    || !relativePath
    || kind !== 'DIRECTORY' && kind !== 'FILE'
    || size !== null && !isNonNegativeSafeInteger(size)
    || !isNonNegativeSafeInteger(lastModified)) {
    throw invalidResponse(status);
  }
  return {
    name,
    relativePath,
    kind,
    size: size as number | null,
    lastModified: lastModified as number,
  };
}

function parseContentResponse(
  value: unknown,
  locator: WorkbenchDocumentLocator,
  status: number,
): WorkbenchDocumentContentView {
  const body = requireRecord(value, status);
  const reference = requireRecord(body.reference, status);
  const kind = body.kind;
  const media = safeMediaType(body.mediaType);
  const encoding = nullableMetadata(body.encoding, MAX_ENCODING_CHARS);
  const contentVersion = boundedMetadata(
    body.contentVersion,
    MAX_CONTENT_VERSION_CHARS,
    false,
  );
  const content = body.content;
  if (reference.repositoryKey !== locator.repositoryKey
    || reference.relativePath !== locator.relativePath
    || typeof kind !== 'string'
    || !DOCUMENT_KINDS.has(kind as WorkbenchDocumentKind)
    || !media
    || encoding === undefined
    || !isNonNegativeSafeInteger(body.size)
    || !isNonNegativeSafeInteger(body.lastModified)
    || !contentVersion
    || content !== null && (typeof content !== 'string' || content.length > MAX_CONTENT_CHARS)
    || typeof body.truncated !== 'boolean'
    || typeof body.deleted !== 'boolean') {
    throw invalidResponse(status);
  }
  return {
    reference: {
      repositoryKey: locator.repositoryKey,
      relativePath: locator.relativePath,
    },
    kind: kind as WorkbenchDocumentKind,
    mediaType: media,
    encoding,
    size: body.size as number,
    lastModified: body.lastModified as number,
    contentVersion,
    content: content as string | null,
    truncated: body.truncated,
    deleted: body.deleted,
  };
}

function requireRecord(value: unknown, status: number): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw invalidResponse(status);
  }
  return value as Record<string, unknown>;
}

function safeRelativeResponsePath(value: unknown): string | null {
  try {
    return requireRelativePath(value, false);
  } catch {
    return null;
  }
}

function boundedMetadata(
  value: unknown,
  maximumChars: number,
  allowEmpty: boolean,
): string | null {
  return typeof value === 'string'
    && (allowEmpty || value.length > 0)
    && value.length <= maximumChars
    && !CONTROL_CHARACTER.test(value)
    ? value
    : null;
}

function nullableMetadata(value: unknown, maximumChars: number): string | null | undefined {
  if (value === null) return null;
  return boundedMetadata(value, maximumChars, false) ?? undefined;
}

function isNonNegativeSafeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function responseEtag(response: Response): string | null {
  const value = response.headers.get('ETag');
  if (value == null) return null;
  const bounded = boundedMetadata(value, MAX_ETAG_CHARS, false);
  if (!bounded) throw invalidResponse(response.status);
  return bounded;
}

async function readBlob(response: Response): Promise<Blob> {
  try {
    return await response.blob();
  } catch {
    throw new WorkbenchDocumentApiError(
      response.status,
      'WORKBENCH_DOCUMENT_RESPONSE_INVALID',
    );
  }
}

function contentDispositionFileName(value: string | null): string | null {
  if (!value
    || value.length > MAX_CONTENT_DISPOSITION_CHARS
    || CONTROL_CHARACTER.test(value)) {
    return null;
  }
  const encoded = dispositionParameter(value, 'filename*');
  if (encoded) {
    const separator = encoded.indexOf("''");
    const payload = separator >= 0 ? encoded.slice(separator + 2) : encoded;
    try {
      const decoded = decodeURIComponent(payload);
      const safe = safeFileName(decoded);
      if (safe) return safe;
    } catch {
      // 无效 RFC 5987 编码降级到普通 filename 或请求路径 basename。
    }
  }
  const plain = dispositionParameter(value, 'filename');
  return plain ? safeFileName(plain) : null;
}

function dispositionParameter(value: string, name: string): string | null {
  const escapedName = name.replace('*', '\\*');
  const match = new RegExp(
    `(?:^|;)\\s*${escapedName}\\s*=\\s*(?:"([^"]*)"|([^;]*))`,
    'i',
  ).exec(value);
  const parameter = match ? (match[1] ?? match[2] ?? '').trim() : '';
  return parameter || null;
}

function safeFileName(value: string): string | null {
  const normalized = value.replace(/\\/g, '/');
  const candidate = normalized.split('/').filter(Boolean).pop() || '';
  const cleaned = candidate.trim();
  if (!cleaned
    || cleaned.length > MAX_FILE_NAME_CHARS
    || cleaned === '.'
    || cleaned === '..'
    || CONTROL_CHARACTER.test(cleaned)) {
    return null;
  }
  return cleaned;
}

function mediaType(header: string | null, blobType: string): string | null {
  return header == null ? safeMediaType(blobType) : safeMediaType(header);
}

function inlineImageMediaType(value: unknown): WorkbenchInlineImageMediaType | null {
  if (typeof value !== 'string' || CONTROL_CHARACTER.test(value)) return null;
  const normalized = value.split(';', 1)[0]?.trim().toLowerCase() || '';
  return INLINE_IMAGE_MEDIA_TYPES.has(normalized as WorkbenchInlineImageMediaType)
    ? normalized as WorkbenchInlineImageMediaType
    : null;
}

function safeMediaType(value: unknown): string | null {
  if (typeof value !== 'string'
    || value.length === 0
    || value.length > MAX_MEDIA_TYPE_CHARS
    || CONTROL_CHARACTER.test(value)) {
    return null;
  }
  const base = value.split(';')[0]?.trim() || '';
  return /^[!#$&^_.+\-A-Za-z0-9]+\/[!#$&^_.+\-A-Za-z0-9]+$/.test(base)
    ? base
    : null;
}

function invalidRequest(): WorkbenchDocumentApiError {
  return new WorkbenchDocumentApiError(400, 'WORKBENCH_DOCUMENT_REQUEST_INVALID');
}

function invalidResponse(status: number): WorkbenchDocumentApiError {
  return new WorkbenchDocumentApiError(status, 'WORKBENCH_DOCUMENT_RESPONSE_INVALID');
}
