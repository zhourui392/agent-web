/**
 * Workbench 浏览器上传附件的 Owner-scoped API client。
 *
 * 客户端只接受逻辑附件投影，任何 storage key、物理路径或未知字段都会 fail closed。
 *
 * @author alex
 * @since 2026-08-01
 */
import { isWorkbenchPhase, type WorkbenchPhase } from '../lib/workbench-state.js';

const IDENTIFIER_MAX_LENGTH = 128;
const DISPLAY_NAME_MAX_LENGTH = 255;
const MEDIA_TYPE_MAX_LENGTH = 128;
const LOWERCASE_SHA_256 = /^[a-f0-9]{64}$/;
const PROJECTION_FIELDS = new Set([
  'attachmentId',
  'displayName',
  'mediaType',
  'size',
  'sha256',
  'expiresAt',
]);

const SAFE_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  AUTHENTICATION_REQUIRED: 'Authentication is required',
  UNAUTHORIZED: 'Authentication is required',
  ACCESS_DENIED: 'Access is denied',
  FORBIDDEN: 'Access is denied',
  WORKBENCH_NOT_FOUND: 'Workbench was not found',
  WORKBENCH_ATTACHMENT_INVALID: 'Uploaded attachment request is invalid',
  WORKBENCH_ATTACHMENT_TOO_LARGE: 'Uploaded attachment exceeds the configured size limit',
  WORKBENCH_ATTACHMENT_LIMIT_EXCEEDED: 'Uploaded attachment limit was exceeded',
  WORKBENCH_ATTACHMENT_UNAVAILABLE: 'Uploaded attachment is unavailable',
  WORKBENCH_ATTACHMENT_STORAGE_UNAVAILABLE: 'Uploaded attachment storage is unavailable',
  WORKBENCH_REQUEST_INVALID: 'Uploaded attachment request is invalid',
};

export type WorkbenchUploadedAttachmentFetch = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

export interface WorkbenchUploadedAttachment {
  attachmentId: string;
  displayName: string;
  mediaType: string;
  size: number;
  sha256: string;
  expiresAt: string;
}

export interface WorkbenchUploadedAttachmentApiClient {
  upload(
    workbenchId: string,
    phase: WorkbenchPhase,
    conversationGeneration: number,
    file: File,
  ): Promise<WorkbenchUploadedAttachment>;
  release(
    workbenchId: string,
    phase: WorkbenchPhase,
    conversationGeneration: number,
    attachmentId: string,
  ): Promise<void>;
}

export class WorkbenchUploadedAttachmentApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'WorkbenchUploadedAttachmentApiError';
    this.status = status;
    this.code = code;
    this.stack = undefined;
  }
}

export function createWorkbenchUploadedAttachmentApiClient(
  fetcher: WorkbenchUploadedAttachmentFetch = fetch,
): WorkbenchUploadedAttachmentApiClient {
  return {
    async upload(workbenchId, phase, conversationGeneration, file) {
      const scoped = attachmentCollectionUrl(
        workbenchId, phase, conversationGeneration,
      );
      requireFile(file);
      const body = new FormData();
      body.append('file', file);
      const response = await fetcher(scoped, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
        body,
      });
      if (response.status !== 201) {
        if (response.ok) throw responseInvalid(response.status);
        throw await responseError(response);
      }
      return parseProjection(response);
    },

    async release(workbenchId, phase, conversationGeneration, attachmentId) {
      const collection = attachmentCollectionUrl(
        workbenchId, phase, conversationGeneration,
      );
      const response = await fetcher(
        `${collection.substring(0, collection.indexOf('?'))}`
          + `/${encodedPathSegment(attachmentId, 'attachmentId')}`
          + `?conversationGeneration=${conversationGeneration}`,
        {
          method: 'DELETE',
          credentials: 'same-origin',
          headers: { Accept: 'application/json' },
        },
      );
      if (response.status !== 204) {
        if (response.ok) throw responseInvalid(response.status);
        throw await responseError(response);
      }
    },
  };
}

function attachmentCollectionUrl(
  workbenchId: string,
  phase: WorkbenchPhase,
  conversationGeneration: number,
): string {
  if (!isWorkbenchPhase(phase)) {
    throw requestInvalid('phase is invalid');
  }
  if (!Number.isSafeInteger(conversationGeneration) || conversationGeneration < 0) {
    throw requestInvalid('conversation generation is invalid');
  }
  return '/api/workbenches/' + encodedPathSegment(workbenchId, 'workbenchId')
    + '/phases/' + phase + '/attachments'
    + `?conversationGeneration=${conversationGeneration}`;
}

function requireFile(file: File): void {
  if (!file || typeof file.name !== 'string'
    || !Number.isSafeInteger(file.size) || file.size < 1) {
    throw requestInvalid('file is invalid');
  }
}

function encodedPathSegment(value: string, name: string): string {
  if (typeof value !== 'string') throw requestInvalid(`${name} is invalid`);
  const normalized = value.trim();
  if (!normalized || normalized === '.' || normalized === '..'
    || normalized.length > IDENTIFIER_MAX_LENGTH) {
    throw requestInvalid(`${name} is invalid`);
  }
  return encodeURIComponent(normalized);
}

async function parseProjection(response: Response): Promise<WorkbenchUploadedAttachment> {
  const body = await parseBody(response);
  if (!isRecord(body) || !hasExactKeys(body, PROJECTION_FIELDS)) {
    throw responseInvalid(response.status);
  }
  const attachmentId = boundedString(body.attachmentId, IDENTIFIER_MAX_LENGTH);
  const displayName = boundedString(body.displayName, DISPLAY_NAME_MAX_LENGTH);
  const mediaType = boundedString(body.mediaType, MEDIA_TYPE_MAX_LENGTH);
  const expiresAt = boundedString(body.expiresAt, IDENTIFIER_MAX_LENGTH);
  if (!attachmentId || !displayName || !mediaType || !expiresAt
    || !Number.isSafeInteger(body.size) || (body.size as number) < 1
    || typeof body.sha256 !== 'string' || !LOWERCASE_SHA_256.test(body.sha256)
    || !Number.isFinite(Date.parse(expiresAt))) {
    throw responseInvalid(response.status);
  }
  return {
    attachmentId,
    displayName,
    mediaType,
    size: body.size as number,
    sha256: body.sha256,
    expiresAt,
  };
}

async function responseError(response: Response): Promise<WorkbenchUploadedAttachmentApiError> {
  const body = await parseBody(response);
  const serverCode = isRecord(body) && typeof body.code === 'string'
    ? body.code
    : '';
  if (SAFE_ERROR_MESSAGES[serverCode]) {
    return new WorkbenchUploadedAttachmentApiError(
      response.status,
      serverCode,
      SAFE_ERROR_MESSAGES[serverCode],
    );
  }
  const fallback = fallbackError(response.status);
  return new WorkbenchUploadedAttachmentApiError(
    response.status,
    fallback.code,
    fallback.message,
  );
}

function fallbackError(status: number): { code: string; message: string } {
  if (status === 401) {
    return { code: 'AUTHENTICATION_REQUIRED', message: SAFE_ERROR_MESSAGES.AUTHENTICATION_REQUIRED };
  }
  if (status === 403) {
    return { code: 'ACCESS_DENIED', message: SAFE_ERROR_MESSAGES.ACCESS_DENIED };
  }
  if (status === 404) {
    return { code: 'WORKBENCH_NOT_FOUND', message: SAFE_ERROR_MESSAGES.WORKBENCH_NOT_FOUND };
  }
  if (status === 413) {
    return {
      code: 'WORKBENCH_ATTACHMENT_TOO_LARGE',
      message: SAFE_ERROR_MESSAGES.WORKBENCH_ATTACHMENT_TOO_LARGE,
    };
  }
  if (status === 503) {
    return {
      code: 'WORKBENCH_ATTACHMENT_STORAGE_UNAVAILABLE',
      message: SAFE_ERROR_MESSAGES.WORKBENCH_ATTACHMENT_STORAGE_UNAVAILABLE,
    };
  }
  return {
    code: 'WORKBENCH_ATTACHMENT_INVALID',
    message: SAFE_ERROR_MESSAGES.WORKBENCH_ATTACHMENT_INVALID,
  };
}

async function parseBody(response: Response): Promise<unknown> {
  let text: string;
  try {
    text = await response.text();
  } catch {
    return null;
  }
  if (!text) return null;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

function hasExactKeys(value: Record<string, unknown>, expected: ReadonlySet<string>): boolean {
  const keys = Object.keys(value);
  return keys.length === expected.size && keys.every(key => expected.has(key));
}

function boundedString(value: unknown, maximum: number): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.trim();
  return normalized && normalized.length <= maximum ? normalized : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value);
}

function requestInvalid(reason: string): WorkbenchUploadedAttachmentApiError {
  return new WorkbenchUploadedAttachmentApiError(
    0,
    'WORKBENCH_ATTACHMENT_REQUEST_INVALID',
    reason,
  );
}

function responseInvalid(status: number): WorkbenchUploadedAttachmentApiError {
  return new WorkbenchUploadedAttachmentApiError(
    status,
    'WORKBENCH_ATTACHMENT_RESPONSE_INVALID',
    'Uploaded attachment service returned an invalid response',
  );
}
