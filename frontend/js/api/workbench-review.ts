/**
 * TD-03/TD-10 Review Opinion 与 exact MODIFY Confirmation Owner API client。
 *
 * 只保留经过白名单校验的版本、Hash 和只读状态；服务端 message、路径及任意扩展字段不会进入错误对象。
 *
 * @author alex
 * @since 2026-08-01
 */
const IDENTIFIER_MAX_CHARS = 128;
const MAX_RESPONSE_CHARS = 1024 * 1024;
const MAX_CANDIDATE_ITEMS = 50;
const MAX_CANDIDATE_FILES = 50;
const MAX_CANDIDATE_TESTS = 20;
const SHA_256 = /^[a-f0-9]{64}$/;
const CONTROL_CHARACTER = /[\u0000-\u001F\u007F]/;
const POSIX_ABSOLUTE_PATH = /(^|[\s(\[{=\"'])\/(?!\/)[^\s]+/;
const WINDOWS_ABSOLUTE_PATH = /(^|[\s([{='"])[A-Za-z]:[\\/][^\s]+/;
const SAFE_ERROR_CODES = new Set([
  'AUTHENTICATION_REQUIRED',
  'ACCESS_DENIED',
  'WORKBENCH_NOT_FOUND',
  'WORKBENCH_ARCHIVED',
  'WORKBENCH_REVIEW_OPINION_NOT_FOUND',
  'WORKBENCH_REVIEW_CONFIRMATION_NOT_FOUND',
  'WORKBENCH_REVIEW_VERSION_CONFLICT',
  'WORKBENCH_REVIEW_REQUEST_INVALID',
  'WORKBENCH_REVIEW_CANDIDATE_REQUEST_INVALID',
  'WORKBENCH_REVIEW_CANDIDATE_SOURCE_UNAVAILABLE',
  'WORKBENCH_CONVERSATION_CONFLICT',
  'WORKBENCH_PHASE_MESSAGE_TOO_LARGE',
]);

export type WorkbenchReviewFetch = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

export interface WorkbenchReviewOpinion {
  phase: 'REVIEW_REFACTOR';
  version: number;
  content: string;
  contentHash: string;
  reviewedAt: number;
  readOnly: boolean;
}

export interface WorkbenchReviewConfirmation {
  confirmationId: string;
  phase: 'REVIEW_REFACTOR';
  opinionVersion: number;
  opinionHash: string;
  confirmedAt: number;
  readOnly: boolean;
}

export interface WorkbenchReviewCandidateDocumentReference {
  repositoryKey: string;
  relativePath: string;
}

export interface WorkbenchReviewCandidateItem {
  itemId: string;
  finding: string;
  impact: string;
  suggestedChange: string;
  affectedFiles: WorkbenchReviewCandidateDocumentReference[];
  suggestedTests: string[];
}

export interface WorkbenchReviewCandidate {
  phase: 'REVIEW_REFACTOR';
  baseOpinionVersion: number;
  conversationGeneration: number;
  sourceMessageCount: number;
  strategy: 'DETERMINISTIC_PUBLIC_REVIEW_MESSAGES_V1';
  items: WorkbenchReviewCandidateItem[];
}

export interface WorkbenchReviewApiClient {
  getOpinion(workbenchId: string): Promise<WorkbenchReviewOpinion | null>;
  saveOpinion(
    workbenchId: string,
    expectedVersion: number,
    content: string,
  ): Promise<WorkbenchReviewOpinion>;
  getConfirmation(workbenchId: string): Promise<WorkbenchReviewConfirmation | null>;
  confirmModification(
    workbenchId: string,
    opinionVersion: number,
    opinionHash: string,
  ): Promise<WorkbenchReviewConfirmation>;
  generateCandidate(workbenchId: string): Promise<WorkbenchReviewCandidate>;
}

export class WorkbenchReviewApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    public readonly current: WorkbenchReviewOpinion | null = null,
  ) {
    super('Workbench review request failed');
    this.name = 'WorkbenchReviewApiError';
    const sanitized = this as Error & {
      cause?: unknown;
      body?: unknown;
      response?: unknown;
    };
    delete sanitized.stack;
    delete sanitized.cause;
    delete sanitized.body;
    delete sanitized.response;
  }
}

export function createWorkbenchReviewApiClient(
  injectedFetch?: WorkbenchReviewFetch,
): WorkbenchReviewApiClient {
  const execute = injectedFetch ?? ((input, init) => globalThis.fetch(input, init));

  return {
    async getOpinion(workbenchId) {
      const response = await safeFetch(execute, opinionUrl(workbenchId), {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      });
      const body = await readBody(response);
      if (response.status === 404 && safeErrorCode(body) === 'WORKBENCH_REVIEW_OPINION_NOT_FOUND') {
        return null;
      }
      if (!response.ok) throw responseError(response.status, body);
      if (response.status !== 200) throw invalidResponse(response.status);
      const projected = opinionProjection(body);
      if (!projected) throw invalidResponse(response.status);
      return projected;
    },

    async saveOpinion(workbenchId, expectedVersion, content) {
      const version = nonNegativeInteger(expectedVersion);
      const exactContent = reviewContent(content);
      if (version == null || exactContent == null) throw invalidRequest();
      const response = await safeFetch(execute, opinionUrl(workbenchId), {
        method: 'PUT',
        credentials: 'same-origin',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'If-Match': String(version),
        },
        body: JSON.stringify({ content: exactContent }),
      });
      const body = await readBody(response);
      if (!response.ok) throw responseError(response.status, body);
      if (response.status !== 200) throw invalidResponse(response.status);
      const projected = opinionProjection(body);
      if (!projected) throw invalidResponse(response.status);
      return projected;
    },

    async getConfirmation(workbenchId) {
      const response = await safeFetch(execute, confirmationUrl(workbenchId), {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      });
      const body = await readBody(response);
      if (
        response.status === 404 &&
        [
          'WORKBENCH_REVIEW_CONFIRMATION_NOT_FOUND',
          'WORKBENCH_REVIEW_OPINION_NOT_FOUND',
        ].includes(safeErrorCode(body) ?? '')
      ) {
        return null;
      }
      if (!response.ok) throw responseError(response.status, body);
      if (response.status !== 200) throw invalidResponse(response.status);
      const projected = confirmationProjection(body);
      if (!projected) throw invalidResponse(response.status);
      return projected;
    },

    async confirmModification(workbenchId, opinionVersion, opinionHash) {
      const version = positiveInteger(opinionVersion);
      const hash = sha256(opinionHash);
      if (version == null || !hash) throw invalidRequest();
      const response = await safeFetch(execute, confirmationUrl(workbenchId), {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ opinionVersion: version, opinionHash: hash }),
      });
      const body = await readBody(response);
      if (!response.ok) throw responseError(response.status, body);
      if (response.status !== 201) throw invalidResponse(response.status);
      const projected = confirmationProjection(body);
      if (
        !projected ||
        projected.opinionVersion !== version ||
        projected.opinionHash !== hash
      ) {
        throw invalidResponse(response.status);
      }
      return projected;
    },

    async generateCandidate(workbenchId) {
      const response = await safeFetch(execute, candidateUrl(workbenchId), {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
        },
        body: '{}',
      });
      const body = await readBody(response);
      if (!response.ok) throw responseError(response.status, body);
      if (response.status !== 200) throw invalidResponse(response.status);
      const projected = candidateProjection(body);
      if (!projected) throw invalidResponse(response.status);
      return projected;
    },
  };
}

function opinionUrl(workbenchId: string): string {
  return `${reviewBaseUrl(workbenchId)}/review-opinion`;
}

function confirmationUrl(workbenchId: string): string {
  return `${reviewBaseUrl(workbenchId)}/review-confirmation`;
}

function candidateUrl(workbenchId: string): string {
  return `${reviewBaseUrl(workbenchId)}/review-candidates`;
}

function reviewBaseUrl(workbenchId: string): string {
  return `/api/workbenches/${encodedIdentifier(workbenchId)}/phases/REVIEW_REFACTOR`;
}

function encodedIdentifier(value: string): string {
  const normalized = boundedString(value, IDENTIFIER_MAX_CHARS);
  if (!normalized || normalized === '.' || normalized === '..') {
    throw new Error('workbenchId is invalid');
  }
  return encodeURIComponent(normalized);
}

function opinionProjection(value: unknown): WorkbenchReviewOpinion | null {
  if (!isRecord(value) || value.phase !== 'REVIEW_REFACTOR') return null;
  const version = positiveInteger(value.version);
  const content = reviewContent(value.content);
  const contentHash = sha256(value.contentHash);
  const reviewedAt = nonNegativeInteger(value.reviewedAt);
  if (
    version == null ||
    content == null ||
    !contentHash ||
    reviewedAt == null ||
    typeof value.readOnly !== 'boolean'
  ) {
    return null;
  }
  return {
    phase: 'REVIEW_REFACTOR',
    version,
    content,
    contentHash,
    reviewedAt,
    readOnly: value.readOnly,
  };
}

function confirmationProjection(value: unknown): WorkbenchReviewConfirmation | null {
  if (!isRecord(value) || value.phase !== 'REVIEW_REFACTOR') return null;
  const confirmationId = boundedString(value.confirmationId, IDENTIFIER_MAX_CHARS);
  const opinionVersion = positiveInteger(value.opinionVersion);
  const opinionHash = sha256(value.opinionHash);
  const confirmedAt = nonNegativeInteger(value.confirmedAt);
  if (
    !confirmationId ||
    opinionVersion == null ||
    !opinionHash ||
    confirmedAt == null ||
    typeof value.readOnly !== 'boolean'
  ) {
    return null;
  }
  return {
    confirmationId,
    phase: 'REVIEW_REFACTOR',
    opinionVersion,
    opinionHash,
    confirmedAt,
    readOnly: value.readOnly,
  };
}

function candidateProjection(value: unknown): WorkbenchReviewCandidate | null {
  if (
    !isRecord(value) ||
    value.phase !== 'REVIEW_REFACTOR' ||
    value.strategy !== 'DETERMINISTIC_PUBLIC_REVIEW_MESSAGES_V1'
  ) return null;
  const baseOpinionVersion = nonNegativeInteger(value.baseOpinionVersion);
  const conversationGeneration = nonNegativeInteger(value.conversationGeneration);
  const sourceMessageCount = nonNegativeInteger(value.sourceMessageCount);
  const items = candidateItems(value.items);
  if (
    baseOpinionVersion == null ||
    conversationGeneration == null ||
    sourceMessageCount == null ||
    sourceMessageCount > 50 ||
    !items
  ) return null;
  return {
    phase: 'REVIEW_REFACTOR',
    baseOpinionVersion,
    conversationGeneration,
    sourceMessageCount,
    strategy: 'DETERMINISTIC_PUBLIC_REVIEW_MESSAGES_V1',
    items,
  };
}

function candidateItems(value: unknown): WorkbenchReviewCandidateItem[] | null {
  if (!Array.isArray(value) || value.length > MAX_CANDIDATE_ITEMS) return null;
  const result: WorkbenchReviewCandidateItem[] = [];
  const identities = new Set<string>();
  for (const candidate of value) {
    if (!isRecord(candidate)) return null;
    const itemId = sha256(candidate.itemId);
    const finding = candidateText(candidate.finding, 2000, false);
    const impact = candidateText(candidate.impact, 4000, true);
    const suggestedChange = candidateText(candidate.suggestedChange, 4000, true);
    const affectedFiles = candidateFiles(candidate.affectedFiles);
    const suggestedTests = candidateTests(candidate.suggestedTests);
    if (
      !itemId ||
      !finding ||
      impact == null ||
      suggestedChange == null ||
      !affectedFiles ||
      !suggestedTests ||
      identities.has(itemId)
    ) return null;
    identities.add(itemId);
    result.push({
      itemId,
      finding,
      impact,
      suggestedChange,
      affectedFiles,
      suggestedTests,
    });
  }
  return result;
}

function candidateFiles(
  value: unknown,
): WorkbenchReviewCandidateDocumentReference[] | null {
  if (!Array.isArray(value) || value.length > MAX_CANDIDATE_FILES) return null;
  const result: WorkbenchReviewCandidateDocumentReference[] = [];
  const identities = new Set<string>();
  for (const candidate of value) {
    if (!isRecord(candidate)) return null;
    const repositoryKey = logicalRepositoryKey(candidate.repositoryKey);
    const relativePath = logicalRelativePath(candidate.relativePath);
    if (!repositoryKey || !relativePath) return null;
    const identity = `${repositoryKey}\u0000${relativePath}`;
    if (identities.has(identity)) return null;
    identities.add(identity);
    result.push({ repositoryKey, relativePath });
  }
  return result;
}

function candidateTests(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.length > MAX_CANDIDATE_TESTS) return null;
  const result: string[] = [];
  const unique = new Set<string>();
  for (const candidate of value) {
    const test = candidateText(candidate, 1000, false);
    if (!test || unique.has(test)) return null;
    unique.add(test);
    result.push(test);
  }
  return result;
}

function candidateText(
  value: unknown,
  maximum: number,
  allowEmpty: boolean,
): string | null {
  if (typeof value !== 'string' || value.length > maximum) return null;
  const normalized = value.trim();
  if ((!allowEmpty && !normalized) || CONTROL_CHARACTER.test(normalized)) return null;
  if (POSIX_ABSOLUTE_PATH.test(normalized) || WINDOWS_ABSOLUTE_PATH.test(normalized)) return null;
  return normalized;
}

function logicalRepositoryKey(value: unknown): string | null {
  const key = boundedString(value, IDENTIFIER_MAX_CHARS);
  if (!key || key === '.' || key === '..' || /[\\/]/.test(key)) return null;
  return key;
}

function logicalRelativePath(value: unknown): string | null {
  const path = boundedString(value, 1024);
  if (!path || path.startsWith('/') || path.startsWith('\\') || path.includes('\\')) return null;
  const segments = path.split('/');
  if (segments.some(segment => !segment || segment === '.' || segment === '..')) return null;
  return path;
}

async function safeFetch(
  fetcher: WorkbenchReviewFetch,
  input: RequestInfo | URL,
  init: RequestInit,
): Promise<Response> {
  try {
    return await fetcher(input, init);
  } catch {
    throw new WorkbenchReviewApiError(0, 'WORKBENCH_REVIEW_NETWORK_ERROR');
  }
}

async function readBody(response: Response): Promise<unknown> {
  let text: string;
  try {
    text = await response.text();
  } catch {
    throw invalidResponse(response.status);
  }
  if (text.length > MAX_RESPONSE_CHARS) throw invalidResponse(response.status);
  if (!text) return null;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

function responseError(status: number, body: unknown): WorkbenchReviewApiError {
  const code = safeErrorCode(body) ?? fallbackCode(status);
  const current = status === 409 && isRecord(body)
    ? opinionProjection(body.current)
    : null;
  return new WorkbenchReviewApiError(status, code, current);
}

function fallbackCode(status: number): string {
  switch (status) {
    case 401:
      return 'AUTHENTICATION_REQUIRED';
    case 403:
      return 'ACCESS_DENIED';
    case 404:
      return 'WORKBENCH_NOT_FOUND';
    case 409:
      return 'WORKBENCH_REVIEW_VERSION_CONFLICT';
    case 410:
      return 'WORKBENCH_ARCHIVED';
    case 400:
      return 'WORKBENCH_REVIEW_REQUEST_INVALID';
    default:
      return 'WORKBENCH_REVIEW_REQUEST_FAILED';
  }
}

function safeErrorCode(body: unknown): string | null {
  if (!isRecord(body) || typeof body.code !== 'string' || !SAFE_ERROR_CODES.has(body.code)) {
    return null;
  }
  return body.code;
}

function invalidRequest(): WorkbenchReviewApiError {
  return new WorkbenchReviewApiError(0, 'WORKBENCH_REVIEW_REQUEST_INVALID');
}

function invalidResponse(status: number): WorkbenchReviewApiError {
  return new WorkbenchReviewApiError(status, 'WORKBENCH_REVIEW_RESPONSE_INVALID');
}

function boundedString(value: unknown, maximum: number): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.trim();
  return normalized && normalized.length <= maximum && !CONTROL_CHARACTER.test(normalized)
    ? normalized
    : null;
}

function reviewContent(value: unknown): string | null {
  if (typeof value !== 'string' || !value.trim() || value.length > 16000) return null;
  return /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/.test(value)
    ? null
    : value.trim();
}

function nonNegativeInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
    ? value
    : null;
}

function positiveInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
    ? value
    : null;
}

function sha256(value: unknown): string | null {
  return typeof value === 'string' && SHA_256.test(value) ? value : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
