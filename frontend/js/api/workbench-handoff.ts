/**
 * TD-07 Phase Handoff owner-scoped transport client。
 *
 * @author alex
 * @since 2026-08-01
 */
import { isWorkbenchPhase, type WorkbenchPhase } from '../lib/workbench-state.js';

const SAFE_ERROR_MESSAGE = 'Workbench handoff request failed';
const IDENTIFIER_MAX_CHARS = 4096;
const REPOSITORY_KEY_MAX_CHARS = 160;
const PATH_MAX_CHARS = 4096;
const SUMMARY_MAX_CHARS = 8000;
const ITEM_TEXT_MAX_CHARS = 2000;
const SAFE_RUN_SUMMARY_MAX_CHARS = 2000;
const MAX_DECISIONS = 50;
const MAX_OPEN_QUESTIONS = 50;
const MAX_PINNED_FILES = 100;
const MAX_REFERENCED_RUNS = 50;
const MAX_SERIALIZED_CONTENT_BYTES = 256 * 1024;
const MAX_RESPONSE_CHARS = 1024 * 1024;
const SHA_256 = /^[a-f0-9]{64}$/;
const WINDOWS_ABSOLUTE_PATH = /^[A-Za-z]:[\\/]/;
const CONTROL_CHARACTER = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/;
const SAFE_ERROR_CODES = new Set([
  'AUTHENTICATION_REQUIRED',
  'ACCESS_DENIED',
  'WORKBENCH_NOT_FOUND',
  'WORKBENCH_ARCHIVED',
  'WORKBENCH_REQUEST_INVALID',
  'WORKBENCH_VERSION_CONFLICT',
  'HANDOFF_NOT_FOUND',
  'WORKBENCH_HANDOFF_NOT_FOUND',
  'WORKBENCH_HANDOFF_ALREADY_EXISTS',
  'WORKBENCH_HANDOFF_VERSION_CONFLICT',
  'WORKBENCH_HANDOFF_SOURCE_CHANGED',
  'WORKBENCH_HANDOFF_SOURCE_NOT_FOUND',
  'WORKBENCH_HANDOFF_RECEPTION_NOT_FOUND',
  'WORKBENCH_HANDOFF_REQUEST_INVALID',
  'WORKBENCH_HANDOFF_SECRET_DETECTED',
  'WORKBENCH_REPOSITORY_SCOPE_INVALID',
  'WORKBENCH_RUN_REFERENCE_INVALID',
]);

export type WorkbenchHandoffFetch = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

export interface HandoffDecision {
  text: string;
  rationale: string | null;
}

export interface HandoffOpenQuestion {
  text: string;
  ownerHint: string | null;
}

export interface HandoffDocumentReference {
  repositoryKey: string;
  relativePath: string;
}

export interface HandoffRunReference {
  runId: string;
  phase: WorkbenchPhase;
  safeSummary: string | null;
}

export interface HandoffEditableContent {
  summary: string;
  decisions: HandoffDecision[];
  openQuestions: HandoffOpenQuestion[];
  pinnedFiles: HandoffDocumentReference[];
  referencedRuns: HandoffRunReference[];
}

export interface PhaseHandoffView extends HandoffEditableContent {
  sourcePhase: WorkbenchPhase;
  version: number;
  contentHash: string;
  updatedAt: number;
  readOnly: boolean;
}

export interface HandoffReceptionView {
  sourcePhase: WorkbenchPhase;
  sourceVersion: number;
  sourceHash: string;
  acceptedAt: number;
}

export interface HandoffCollectionDiff {
  added: number;
  removed: number;
}

export interface HandoffDiffSummary {
  summaryChanged: boolean;
  decisions: HandoffCollectionDiff;
  openQuestions: HandoffCollectionDiff;
  pinnedFiles: HandoffCollectionDiff;
  referencedRuns: HandoffCollectionDiff;
}

export interface HandoffSourceView {
  targetPhase: WorkbenchPhase;
  latestSource: PhaseHandoffView | null;
  reception: HandoffReceptionView | null;
  acceptedSource: PhaseHandoffView | null;
  stale: boolean;
  diff: HandoffDiffSummary | null;
}

export interface AcceptHandoffReceptionInput {
  sourcePhase: WorkbenchPhase;
  sourceVersion: number;
  sourceHash: string;
}

export interface WorkbenchHandoffApiClient {
  getHandoff(workbenchId: string, phase: WorkbenchPhase): Promise<PhaseHandoffView | null>;
  putHandoff(
    workbenchId: string,
    phase: WorkbenchPhase,
    expectedVersion: number,
    content: HandoffEditableContent,
  ): Promise<PhaseHandoffView>;
  getHandoffSource(workbenchId: string, targetPhase: WorkbenchPhase): Promise<HandoffSourceView>;
  acceptHandoffReception(
    workbenchId: string,
    targetPhase: WorkbenchPhase,
    input: AcceptHandoffReceptionInput,
  ): Promise<HandoffReceptionView>;
}

export class WorkbenchHandoffApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    public readonly current: PhaseHandoffView | null = null,
  ) {
    super(SAFE_ERROR_MESSAGE);
    this.name = 'WorkbenchHandoffApiError';
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

export function createWorkbenchHandoffApiClient(injectedFetch?: WorkbenchHandoffFetch): WorkbenchHandoffApiClient {
  const execute = injectedFetch ?? ((input, init) => globalThis.fetch(input, init));

  return {
    async getHandoff(workbenchId, phase) {
      const response = await safeFetch(execute, handoffUrl(workbenchId, phase), {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      });
      const body = await readBody(response);
      if (
        response.status === 404 &&
        ['HANDOFF_NOT_FOUND', 'WORKBENCH_HANDOFF_NOT_FOUND'].includes(safeErrorCode(body) ?? '')
      ) {
        return null;
      }
      if (!response.ok) throw responseError(response.status, body, phase);
      const projected = handoffProjection(body);
      if (!projected || projected.sourcePhase !== phase) {
        throw invalidResponse(response.status);
      }
      return projected;
    },

    async putHandoff(workbenchId, phase, expectedVersion, content) {
      const version = nonNegativeInteger(expectedVersion);
      const request = editableContentProjection(content);
      if (version == null || !request) throw invalidRequest();
      const requestBody = JSON.stringify(writableContent(request));
      if (new TextEncoder().encode(requestBody).byteLength > MAX_SERIALIZED_CONTENT_BYTES) {
        throw invalidRequest();
      }
      const response = await safeFetch(execute, handoffUrl(workbenchId, phase), {
        method: 'PUT',
        credentials: 'same-origin',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'If-Match': String(version),
        },
        body: requestBody,
      });
      const body = await readBody(response);
      if (!response.ok) throw responseError(response.status, body, phase);
      const projected = handoffProjection(body);
      if (!projected || projected.sourcePhase !== phase) {
        throw invalidResponse(response.status);
      }
      return projected;
    },

    async getHandoffSource(workbenchId, targetPhase) {
      const response = await safeFetch(execute, sourceUrl(workbenchId, targetPhase), {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      });
      const body = await readBody(response);
      if (!response.ok) throw responseError(response.status, body);
      const projected = sourceProjection(body);
      if (!projected || projected.targetPhase !== targetPhase) {
        throw invalidResponse(response.status);
      }
      return projected;
    },

    async acceptHandoffReception(workbenchId, targetPhase, input) {
      const request = receptionInputProjection(input);
      if (!request) throw invalidRequest();
      const response = await safeFetch(execute, receptionUrl(workbenchId, targetPhase), {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(request),
      });
      const body = await readBody(response);
      if (!response.ok) throw responseError(response.status, body);
      const projected = receptionProjection(body);
      if (
        !projected ||
        projected.sourcePhase !== request.sourcePhase ||
        projected.sourceVersion !== request.sourceVersion ||
        projected.sourceHash !== request.sourceHash
      ) {
        throw invalidResponse(response.status);
      }
      return projected;
    },
  };
}

function handoffUrl(workbenchId: string, phase: WorkbenchPhase): string {
  return phaseUrl(workbenchId, phase, 'handoff');
}

function sourceUrl(workbenchId: string, phase: WorkbenchPhase): string {
  return phaseUrl(workbenchId, phase, 'handoff-source');
}

function receptionUrl(workbenchId: string, phase: WorkbenchPhase): string {
  return phaseUrl(workbenchId, phase, 'handoff-receptions');
}

function phaseUrl(
  workbenchId: string,
  phase: WorkbenchPhase,
  resource: 'handoff' | 'handoff-source' | 'handoff-receptions',
): string {
  const id = pathSegment(workbenchId);
  if (!isWorkbenchPhase(phase)) throw invalidRequest();
  return `/api/workbenches/${id}/phases/${encodeURIComponent(phase)}/${resource}`;
}

function pathSegment(value: unknown): string {
  const identifier = boundedText(value, IDENTIFIER_MAX_CHARS, false);
  if (!identifier || identifier === '.' || identifier === '..') throw invalidRequest();
  return encodeURIComponent(identifier);
}

function writableContent(content: HandoffEditableContent): Record<string, unknown> {
  return {
    summary: content.summary,
    decisions: content.decisions.map((item) => ({ ...item })),
    openQuestions: content.openQuestions.map((item) => ({ ...item })),
    pinnedFiles: content.pinnedFiles.map((item) => ({ ...item })),
    referencedRuns: content.referencedRuns.map((item) => ({ runId: item.runId })),
  };
}

function handoffProjection(value: unknown): PhaseHandoffView | null {
  const body = record(value);
  if (!body || !isWorkbenchPhase(body.sourcePhase)) return null;
  const editable = editableContentProjection(body);
  const version = nonNegativeInteger(body.version);
  const contentHash = sha256(body.contentHash);
  const updatedAt = nonNegativeInteger(body.updatedAt);
  if (!editable || version == null || !contentHash || updatedAt == null || typeof body.readOnly !== 'boolean') {
    return null;
  }
  return {
    sourcePhase: body.sourcePhase,
    ...editable,
    version,
    contentHash,
    updatedAt,
    readOnly: body.readOnly,
  };
}

function editableContentProjection(value: unknown): HandoffEditableContent | null {
  const body = record(value);
  if (!body) return null;
  const summary = boundedText(body.summary, SUMMARY_MAX_CHARS, true);
  const decisions = decisionList(body.decisions);
  const openQuestions = openQuestionList(body.openQuestions);
  const pinnedFiles = documentList(body.pinnedFiles);
  const referencedRuns = runList(body.referencedRuns);
  if (summary == null || !decisions || !openQuestions || !pinnedFiles || !referencedRuns) {
    return null;
  }
  return { summary, decisions, openQuestions, pinnedFiles, referencedRuns };
}

function decisionList(value: unknown): HandoffDecision[] | null {
  if (!Array.isArray(value) || value.length > MAX_DECISIONS) return null;
  const result: HandoffDecision[] = [];
  for (const raw of value) {
    const item = record(raw);
    if (!item) return null;
    const text = boundedText(item.text, ITEM_TEXT_MAX_CHARS, false);
    const rationale = nullableBoundedText(item.rationale, ITEM_TEXT_MAX_CHARS);
    if (!text || rationale === undefined) return null;
    result.push({ text, rationale });
  }
  return result;
}

function openQuestionList(value: unknown): HandoffOpenQuestion[] | null {
  if (!Array.isArray(value) || value.length > MAX_OPEN_QUESTIONS) return null;
  const result: HandoffOpenQuestion[] = [];
  for (const raw of value) {
    const item = record(raw);
    if (!item) return null;
    const text = boundedText(item.text, ITEM_TEXT_MAX_CHARS, false);
    const ownerHint = nullableBoundedText(item.ownerHint, ITEM_TEXT_MAX_CHARS);
    if (!text || ownerHint === undefined) return null;
    result.push({ text, ownerHint });
  }
  return result;
}

function documentList(value: unknown): HandoffDocumentReference[] | null {
  if (!Array.isArray(value) || value.length > MAX_PINNED_FILES) return null;
  const result: HandoffDocumentReference[] = [];
  const unique = new Set<string>();
  for (const raw of value) {
    const item = record(raw);
    if (!item) return null;
    const repositoryKey = boundedText(item.repositoryKey, REPOSITORY_KEY_MAX_CHARS, false);
    const relativePath = relativeDocumentPath(item.relativePath);
    if (!repositoryKey || !relativePath) return null;
    const key = `${repositoryKey}\u0000${relativePath}`;
    if (!unique.add(key)) return null;
    result.push({ repositoryKey, relativePath });
  }
  return result;
}

function runList(value: unknown): HandoffRunReference[] | null {
  if (!Array.isArray(value) || value.length > MAX_REFERENCED_RUNS) return null;
  const result: HandoffRunReference[] = [];
  const unique = new Set<string>();
  for (const raw of value) {
    const item = record(raw);
    if (!item) return null;
    const runId = boundedText(item.runId, IDENTIFIER_MAX_CHARS, false);
    const safeSummary = nullableBoundedText(item.safeSummary, SAFE_RUN_SUMMARY_MAX_CHARS);
    if (!runId || !isWorkbenchPhase(item.phase) || safeSummary === undefined || !unique.add(runId)) {
      return null;
    }
    result.push({ runId, phase: item.phase, safeSummary });
  }
  return result;
}

function sourceProjection(value: unknown): HandoffSourceView | null {
  const body = record(value);
  if (!body || !isWorkbenchPhase(body.targetPhase) || typeof body.stale !== 'boolean') {
    return null;
  }
  const latestSource = nullableHandoff(body.latestSource);
  const reception = nullableReception(body.reception);
  const acceptedSource = nullableHandoff(body.acceptedSource);
  const diff = nullableDiff(body.diff);
  if (latestSource === undefined || reception === undefined || acceptedSource === undefined || diff === undefined) {
    return null;
  }
  if (
    reception &&
    acceptedSource &&
    (reception.sourcePhase !== acceptedSource.sourcePhase ||
      reception.sourceVersion !== acceptedSource.version ||
      reception.sourceHash !== acceptedSource.contentHash)
  ) {
    return null;
  }
  return {
    targetPhase: body.targetPhase,
    latestSource,
    reception,
    acceptedSource,
    stale: body.stale,
    diff,
  };
}

function receptionInputProjection(value: unknown): AcceptHandoffReceptionInput | null {
  const body = record(value);
  if (!body || !isWorkbenchPhase(body.sourcePhase)) return null;
  const sourceVersion = nonNegativeInteger(body.sourceVersion);
  const sourceHash = sha256(body.sourceHash);
  return sourceVersion == null || !sourceHash ? null : { sourcePhase: body.sourcePhase, sourceVersion, sourceHash };
}

function receptionProjection(value: unknown): HandoffReceptionView | null {
  const body = record(value);
  const input = receptionInputProjection(value);
  if (!body || !input) return null;
  const acceptedAt = nonNegativeInteger(body.acceptedAt);
  return acceptedAt == null ? null : { ...input, acceptedAt };
}

function nullableHandoff(value: unknown): PhaseHandoffView | null | undefined {
  if (value === null) return null;
  return handoffProjection(value) ?? undefined;
}

function nullableReception(value: unknown): HandoffReceptionView | null | undefined {
  if (value === null) return null;
  return receptionProjection(value) ?? undefined;
}

function nullableDiff(value: unknown): HandoffDiffSummary | null | undefined {
  if (value === null) return null;
  const body = record(value);
  if (!body || typeof body.summaryChanged !== 'boolean') return undefined;
  const decisions = collectionDiff(body.decisions);
  const openQuestions = collectionDiff(body.openQuestions);
  const pinnedFiles = collectionDiff(body.pinnedFiles);
  const referencedRuns = collectionDiff(body.referencedRuns);
  if (!decisions || !openQuestions || !pinnedFiles || !referencedRuns) return undefined;
  return {
    summaryChanged: body.summaryChanged,
    decisions,
    openQuestions,
    pinnedFiles,
    referencedRuns,
  };
}

function collectionDiff(value: unknown): HandoffCollectionDiff | null {
  const body = record(value);
  if (!body) return null;
  const added = nonNegativeInteger(body.added);
  const removed = nonNegativeInteger(body.removed);
  return added == null || removed == null ? null : { added, removed };
}

function relativeDocumentPath(value: unknown): string | null {
  const path = boundedText(value, PATH_MAX_CHARS, false);
  if (!path || path.startsWith('/') || WINDOWS_ABSOLUTE_PATH.test(path) || path.includes('\\')) {
    return null;
  }
  const segments = path.split('/');
  return segments.some((segment) => !segment || segment === '.' || segment === '..') ? null : path;
}

function boundedText(value: unknown, maximumChars: number, allowEmpty: boolean): string | null {
  if (
    typeof value !== 'string' ||
    value.length > maximumChars ||
    CONTROL_CHARACTER.test(value) ||
    (!allowEmpty && value.length === 0)
  ) {
    return null;
  }
  return value;
}

function nullableBoundedText(value: unknown, maximumChars: number): string | null | undefined {
  if (value === null) return null;
  return boundedText(value, maximumChars, true) ?? undefined;
}

function sha256(value: unknown): string | null {
  return typeof value === 'string' && SHA_256.test(value) ? value : null;
}

function nonNegativeInteger(value: unknown): number | null {
  return Number.isSafeInteger(value) && (value as number) >= 0 ? (value as number) : null;
}

function record(value: unknown): Record<string, unknown> | null {
  return value != null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

async function safeFetch(execute: WorkbenchHandoffFetch, input: string, init: RequestInit): Promise<Response> {
  try {
    return await execute(input, init);
  } catch {
    throw new WorkbenchHandoffApiError(0, 'WORKBENCH_HANDOFF_REQUEST_FAILED');
  }
}

async function readBody(response: Response): Promise<unknown> {
  try {
    const text = await response.text();
    if (!text || text.length > MAX_RESPONSE_CHARS) return null;
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

function safeErrorCode(value: unknown): string | null {
  const body = record(value);
  return body && typeof body.code === 'string' && SAFE_ERROR_CODES.has(body.code) ? body.code : null;
}

function responseError(status: number, value: unknown, expectedPhase?: WorkbenchPhase): WorkbenchHandoffApiError {
  const body = record(value);
  const code = safeErrorCode(value) ?? 'WORKBENCH_HANDOFF_REQUEST_FAILED';
  const current = status === 409 && body ? nullableHandoff(body.current) : null;
  const safeCurrent =
    current && current !== undefined && (!expectedPhase || current.sourcePhase === expectedPhase) ? current : null;
  return new WorkbenchHandoffApiError(status, code, safeCurrent);
}

function invalidRequest(): WorkbenchHandoffApiError {
  return new WorkbenchHandoffApiError(400, 'WORKBENCH_HANDOFF_REQUEST_INVALID');
}

function invalidResponse(status: number): WorkbenchHandoffApiError {
  return new WorkbenchHandoffApiError(status, 'WORKBENCH_HANDOFF_RESPONSE_INVALID');
}
