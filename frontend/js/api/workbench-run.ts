/**
 * TD-03 Workbench Run owner-scoped transport client。
 *
 * 错误边界只保留安全状态、代码和固定消息，不暴露服务端响应正文或底层异常。
 *
 * @author alex
 * @since 2026-08-01
 */
import type { WorkbenchRunMode, WorkbenchRunStatus } from '../lib/workbench-run-state.js';
import {
  isWorkbenchStageInstanceIdentifier,
  type WorkbenchStageStatus,
} from '../lib/workbench-state.js';

const IDENTIFIER_MAX_LENGTH = 128;
const HASH_MAX_LENGTH = 256;
const SAFE_SUMMARY_MAX_LENGTH = 2_000;
const EVENT_TYPE_MAX_LENGTH = 80;
const EVENT_PAYLOAD_MAX_LENGTH = 131_072;
const HISTORY_LIST_MAX_LIMIT = 100;
const EVENT_PAGE_MAX_LIMIT = 500;
const CONVERSATION_MESSAGE_MAX_COUNT = 10_000;
const CONVERSATION_CONTENT_MAX_LENGTH = 1_000_000;
const TIMESTAMP_MAX_LENGTH = 128;
const ATTACHMENT_REPOSITORY_KEY_MAX_LENGTH = 512;
const ATTACHMENT_RELATIVE_PATH_MAX_LENGTH = 4_096;
const ATTACHMENT_ID_MAX_LENGTH = 128;
const ATTACHMENT_MAX_COUNT = 8;
const RUN_REPOSITORY_KEY_MAX_LENGTH = 512;
const RUN_REPOSITORY_RELATIVE_PATH_MAX_LENGTH = 4_096;
const RUN_REPOSITORY_MAX_COUNT = 50;
const LOWERCASE_SHA_256 = /^[a-f0-9]{64}$/;
const STAGE_INSTANCE_IDENTIFIER = /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/;

const RUN_STATUSES = new Set<WorkbenchRunStatus>([
  'PENDING',
  'RUNNING',
  'CANCEL_REQUESTED',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'INTERRUPTED',
]);

const RUN_MODES = new Set<WorkbenchRunMode>(['DISCUSS_READ_ONLY', 'MODIFY_WORKSPACE']);

const STAGE_STATUSES = new Set<WorkbenchStageStatus>([
  'NOT_STARTED', 'IN_PROGRESS', 'HUMAN_COMPLETED',
]);

const RUN_REPOSITORY_ACCESSES = new Set<WorkbenchRunRepositoryAccess>(['READ', 'WRITE']);
const RUN_REPOSITORY_FIELDS = new Set(['repositoryKey', 'relativePath', 'primary', 'access']);
const FORBIDDEN_CAPABILITY_SCOPE_FIELDS = new Set([
  'workspaceRoot',
  'repositoryRoot',
  'absolutePath',
  'workingDir',
  'command',
  'args',
  'env',
  'executable',
]);

const ERROR_DETAILS: Readonly<Record<number, { code: string; message: string }>> = {
  401: {
    code: 'AUTHENTICATION_REQUIRED',
    message: 'Authentication is required',
  },
  403: {
    code: 'ACCESS_DENIED',
    message: 'Access is denied',
  },
  404: {
    code: 'WORKBENCH_RUN_NOT_FOUND',
    message: 'Workbench Run was not found',
  },
  409: {
    code: 'WORKBENCH_RUN_CONFLICT',
    message: 'Workbench Run request conflicts with current state',
  },
  410: {
    code: 'WORKBENCH_RUN_CURSOR_EXPIRED',
    message: 'Workbench Run event cursor has expired',
  },
  413: {
    code: 'WORKBENCH_RUN_REQUEST_TOO_LARGE',
    message: 'Workbench Stage conversation message is too large',
  },
  422: {
    code: 'WORKBENCH_RUN_INVALID',
    message: 'Workbench Run request is invalid',
  },
  503: {
    code: 'WORKBENCH_RUN_UNAVAILABLE',
    message: 'Workbench Run service is unavailable',
  },
};

const SAFE_SERVER_ERROR_CODES = new Set([
  'AUTHENTICATION_REQUIRED',
  'UNAUTHORIZED',
  'ACCESS_DENIED',
  'FORBIDDEN',
  'WORKBENCH_NOT_FOUND',
  'WORKBENCH_RUN_NOT_FOUND',
  'RUN_NOT_FOUND',
  'WORKBENCH_VERSION_CONFLICT',
  'IDEMPOTENCY_CONFLICT',
  'WORKBENCH_RUN_CONFLICT',
  'ACTIVE_RUN_CONFLICT',
  'ACTIVE_WRITE_RUN_CONFLICT',
  'WORKSPACE_TOPOLOGY_CHANGED',
  'WORKSPACE_REPOSITORY_NOT_FOUND',
  'WORKBENCH_REPOSITORY_SCOPE_INVALID',
  'REPOSITORY_SCOPE_VIOLATION',
  'WORKBENCH_ATTACHMENT_INVALID',
  'WORKBENCH_ATTACHMENT_TOO_LARGE',
  'WORKBENCH_ATTACHMENT_LIMIT_EXCEEDED',
  'WORKBENCH_ATTACHMENT_UNAVAILABLE',
  'WORKBENCH_RUN_CURSOR_EXPIRED',
  'CURSOR_EXPIRED',
  'WORKBENCH_RUN_INVALID',
  'VALIDATION_ERROR',
  'INVALID_REQUEST',
  'WORKBENCH_RUN_UNAVAILABLE',
  'RUNTIME_UNAVAILABLE',
  'SERVICE_UNAVAILABLE',
  'WORKBENCH_STAGE_MESSAGE_TOO_LARGE',
  'WORKBENCH_ATTACHMENT_STORAGE_UNAVAILABLE',
]);

export type WorkbenchRunFetch = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

export interface WorkbenchRepositoryDocumentAttachment {
  type?: 'REPOSITORY_DOCUMENT';
  repositoryKey: string;
  relativePath: string;
  contentHash: string;
}

export interface WorkbenchUploadedConversationAttachment {
  type: 'UPLOADED_CONVERSATION';
  attachmentId: string;
  contentHash: string;
}

export type WorkbenchRunAttachment =
  | WorkbenchRepositoryDocumentAttachment
  | WorkbenchUploadedConversationAttachment;

export interface SubmitWorkbenchRunRequest {
  message: string;
  runMode: WorkbenchRunMode;
  attachments?: ReadonlyArray<WorkbenchRunAttachment>;
}

export interface SubmitWorkbenchRunCommand {
  workbenchId: string;
  stageInstanceIdentifier: string;
  expectedVersion: number;
  idempotencyKey: string;
  request: SubmitWorkbenchRunRequest;
}

export interface WorkbenchRunSubmission {
  runId: string;
  sessionId: string;
  status: WorkbenchRunStatus;
  stageStatus: WorkbenchStageStatus;
  workbenchVersion: number;
  capabilitySnapshotHash: string;
  repositoryScopeHash: string;
  replayed: boolean;
}

export interface WorkbenchStageConversation {
  sessionId: string;
  generation: number;
  workbenchVersion: number;
  created: boolean;
}

export interface WorkbenchStageConversationMessage {
  messageId: number;
  role: 'user' | 'assistant';
  content: string;
  timestamp: string;
  runId: string | null;
}

export interface WorkbenchStageConversationMessages {
  sessionId: string | null;
  generation: number;
  workbenchVersion: number;
  messages: WorkbenchStageConversationMessage[];
  nextCursor: number | null;
}

export interface WorkbenchStageConversationRestart {
  sessionId: string;
  previousSessionId: string;
  generation: number;
  workbenchVersion: number;
  replayed: boolean;
}

export interface WorkbenchRunDetail {
  runId: string;
  workbenchId: string;
  stageInstanceIdentifier: string;
  sessionId: string;
  status: WorkbenchRunStatus;
  runMode: WorkbenchRunMode;
  lastEventSeq: number;
  earliestRetainedSeq?: number;
  createdAt?: number;
  startedAt?: number | null;
  finishedAt?: number | null;
  failureCode?: string | null;
}

export interface WorkbenchRunHistoryItem {
  runId: string;
  workbenchId: string;
  stageInstanceIdentifier: string;
  sessionId: string;
  status: WorkbenchRunStatus;
  runMode: WorkbenchRunMode;
  lastEventSeq: number;
  earliestRetainedSeq?: number;
  createdAt: number;
  startedAt: number | null;
  finishedAt: number | null;
  failureCode: string | null;
}

export interface WorkbenchRunHistoryCursor {
  createdAt: number;
  runId: string;
}

export interface WorkbenchRunHistoryQuery {
  stageInstanceIdentifier?: string;
  cursorCreatedAt?: number;
  cursorRunId?: string;
  limit?: number;
}

export interface WorkbenchRunHistoryPage {
  items: WorkbenchRunHistoryItem[];
  nextCursor: WorkbenchRunHistoryCursor | null;
}

export interface WorkbenchRunHistoricalEvent {
  sequence: number;
  eventType: string;
  payload: string;
}

export interface WorkbenchRunEventPageQuery {
  after: number;
  limit: number;
}

export interface WorkbenchRunEventPage {
  runId: string;
  after: number;
  through: number;
  lastEventSeq: number;
  earliestRetainedSeq: number;
  hasMore: boolean;
  events: WorkbenchRunHistoricalEvent[];
}

export interface WorkbenchRunCapabilityRule {
  id: string;
  version: string;
  source: string;
  contentHash: string;
  mandatory: boolean;
  safeSummary: string;
}

export interface WorkbenchRunCapabilitySkill {
  id: string;
  version: string;
  source: string;
  packageHash: string;
  trustTier: string;
}

export interface WorkbenchRunCapabilityMcpServer {
  id: string;
  version: string;
  definitionHash: string;
  access: string;
  transport: string;
}

export interface WorkbenchRunRejectedCapability {
  id: string;
  reasonCode: string;
}

export type WorkbenchRunRepositoryAccess = 'READ' | 'WRITE';

export interface WorkbenchRunRepository {
  repositoryKey: string;
  relativePath: string;
  primary: boolean;
  access: WorkbenchRunRepositoryAccess;
}

export interface WorkbenchRunCapability {
  runId: string;
  workbenchId: string;
  stageInstanceIdentifier: string;
  runMode: WorkbenchRunMode;
  createdAt: number;
  policyVersion: string;
  profileId: string;
  profileVersion: string;
  profileHash: string;
  bindingHash: string;
  runtimeCompatibility: string;
  repositoryScopeHash: string;
  primaryRepositoryKey: string;
  repositories: WorkbenchRunRepository[];
  rules: WorkbenchRunCapabilityRule[];
  skills: WorkbenchRunCapabilitySkill[];
  mcpServers: WorkbenchRunCapabilityMcpServer[];
  rejected: WorkbenchRunRejectedCapability[];
}

export interface WorkbenchRunStopResponse {
  runId: string;
  status: WorkbenchRunStatus;
}

export class WorkbenchRunApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'WorkbenchRunApiError';
    this.status = status;
    this.code = code;
    this.stack = undefined;
  }
}

export interface WorkbenchRunApiClient {
  getStageConversationMessages(
    workbenchId: string,
    stageInstanceIdentifier: string,
    beforeMessageId?: number,
  ): Promise<WorkbenchStageConversationMessages>;
  ensureStageConversation(
    workbenchId: string,
    stageInstanceIdentifier: string,
    expectedVersion: number,
  ): Promise<WorkbenchStageConversation>;
  restartStageConversation(
    workbenchId: string,
    stageInstanceIdentifier: string,
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<WorkbenchStageConversationRestart>;
  submitRun(command: SubmitWorkbenchRunCommand): Promise<WorkbenchRunSubmission>;
  getRun(workbenchId: string, runId: string): Promise<WorkbenchRunDetail>;
  stopRun(workbenchId: string, runId: string): Promise<WorkbenchRunStopResponse>;
  listRuns(
    workbenchId: string,
    filters?: WorkbenchRunHistoryQuery,
  ): Promise<WorkbenchRunHistoryPage>;
  getRunEvents(
    workbenchId: string,
    runId: string,
    filters: WorkbenchRunEventPageQuery,
  ): Promise<WorkbenchRunEventPage>;
  getRunCapability(
    workbenchId: string,
    runId: string,
  ): Promise<WorkbenchRunCapability>;
  eventsUrl(workbenchId: string, runId: string): string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function boundedString(value: unknown, maximum: number): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.trim();
  if (!normalized || normalized.length > maximum || /[\u0000-\u001F\u007F]/.test(normalized)) {
    return null;
  }
  return normalized;
}

function logicalRelativePath(value: unknown, maximum: number): string | null {
  const normalized = boundedString(value, maximum);
  if (!normalized || normalized.includes('\\') || normalized.startsWith('/')
    || /^[A-Za-z]:/.test(normalized)) {
    return null;
  }
  const segments = normalized.split('/');
  return segments.some(segment => !segment || segment === '.' || segment === '..')
    ? null : normalized;
}

function hasOnlyFields(body: Record<string, unknown>, allowed: Set<string>): boolean {
  return Object.keys(body).every(field => allowed.has(field));
}

function hasForbiddenCapabilityScopeField(body: Record<string, unknown>): boolean {
  return Object.keys(body).some(field => FORBIDDEN_CAPABILITY_SCOPE_FIELDS.has(field));
}

function nonNegativeInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function positiveInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : null;
}

function nullableNonNegativeInteger(value: unknown): number | null | undefined {
  if (value === null) return null;
  const projected = nonNegativeInteger(value);
  return projected == null ? undefined : projected;
}

function nullableBoundedString(value: unknown, maximum: number): string | null | undefined {
  if (value === null) return null;
  const projected = boundedString(value, maximum);
  return projected == null ? undefined : projected;
}

function knownValue<T extends string>(value: unknown, values: Set<T>): T | null {
  return typeof value === 'string' && values.has(value as T) ? (value as T) : null;
}

function submissionProjection(body: unknown): WorkbenchRunSubmission | null {
  if (!isRecord(body)) return null;
  const runId = boundedString(body.runId, IDENTIFIER_MAX_LENGTH);
  const sessionId = boundedString(body.sessionId, IDENTIFIER_MAX_LENGTH);
  const status = knownValue(body.status, RUN_STATUSES);
  const stageStatus = knownValue(body.stageStatus, STAGE_STATUSES);
  const workbenchVersion = nonNegativeInteger(body.workbenchVersion);
  const capabilitySnapshotHash = boundedString(body.capabilitySnapshotHash, HASH_MAX_LENGTH);
  const repositoryScopeHash = boundedString(body.repositoryScopeHash, HASH_MAX_LENGTH);
  if (
    !runId ||
    !sessionId ||
    !status ||
    !stageStatus ||
    workbenchVersion == null ||
    !capabilitySnapshotHash ||
    !repositoryScopeHash ||
    typeof body.replayed !== 'boolean'
  ) {
    return null;
  }
  return {
    runId,
    sessionId,
    status,
    stageStatus,
    workbenchVersion,
    capabilitySnapshotHash,
    repositoryScopeHash,
    replayed: body.replayed,
  };
}

function conversationProjection(body: unknown): WorkbenchStageConversation | null {
  if (!isRecord(body)) return null;
  const sessionId = boundedString(body.sessionId, IDENTIFIER_MAX_LENGTH);
  const generation = nonNegativeInteger(body.generation);
  const workbenchVersion = nonNegativeInteger(body.workbenchVersion);
  if (!sessionId || generation == null || workbenchVersion == null
    || typeof body.created !== 'boolean') {
    return null;
  }
  return {
    sessionId,
    generation,
    workbenchVersion,
    created: body.created,
  };
}

function conversationMessageProjection(body: unknown): WorkbenchStageConversationMessage | null {
  if (!isRecord(body)) return null;
  const messageId = positiveInteger(body.messageId);
  const role = body.role === 'user' || body.role === 'assistant' ? body.role : null;
  const content = typeof body.content === 'string'
    && body.content.length <= CONVERSATION_CONTENT_MAX_LENGTH ? body.content : null;
  const timestamp = boundedString(body.timestamp, TIMESTAMP_MAX_LENGTH);
  const runId = nullableBoundedString(body.runId, IDENTIFIER_MAX_LENGTH);
  if (messageId == null || !role || content == null || !timestamp || runId === undefined
    || !Number.isFinite(Date.parse(timestamp))) {
    return null;
  }
  return { messageId, role, content, timestamp, runId };
}

function conversationMessagesProjection(body: unknown): WorkbenchStageConversationMessages | null {
  if (!isRecord(body) || !Array.isArray(body.messages)
    || body.messages.length > CONVERSATION_MESSAGE_MAX_COUNT) {
    return null;
  }
  const sessionId = nullableBoundedString(body.sessionId, IDENTIFIER_MAX_LENGTH);
  const generation = nonNegativeInteger(body.generation);
  const workbenchVersion = nonNegativeInteger(body.workbenchVersion);
  const nextCursor = body.nextCursor === null
    ? null : positiveInteger(body.nextCursor);
  if (sessionId === undefined || generation == null || workbenchVersion == null
    || nextCursor === null && body.nextCursor !== null
    || sessionId === null && (body.messages.length > 0 || nextCursor != null)) {
    return null;
  }
  const messages: WorkbenchStageConversationMessage[] = [];
  let previousMessageId = 0;
  for (const candidate of body.messages) {
    const message = conversationMessageProjection(candidate);
    if (!message || message.messageId <= previousMessageId) return null;
    messages.push(message);
    previousMessageId = message.messageId;
  }
  if (nextCursor != null
    && (messages.length === 0 || nextCursor !== messages[0].messageId)) {
    return null;
  }
  return { sessionId, generation, workbenchVersion, messages, nextCursor };
}

function conversationRestartProjection(body: unknown): WorkbenchStageConversationRestart | null {
  if (!isRecord(body)) return null;
  const sessionId = boundedString(body.sessionId, IDENTIFIER_MAX_LENGTH);
  const previousSessionId = boundedString(body.previousSessionId, IDENTIFIER_MAX_LENGTH);
  const generation = positiveInteger(body.generation);
  const workbenchVersion = nonNegativeInteger(body.workbenchVersion);
  if (!sessionId || !previousSessionId || sessionId === previousSessionId
    || generation == null || workbenchVersion == null || typeof body.replayed !== 'boolean') {
    return null;
  }
  return {
    sessionId,
    previousSessionId,
    generation,
    workbenchVersion,
    replayed: body.replayed,
  };
}

function detailProjection(body: unknown): WorkbenchRunDetail | null {
  if (!isRecord(body)) return null;
  const runId = boundedString(body.runId, IDENTIFIER_MAX_LENGTH);
  const workbenchId = boundedString(body.workbenchId, IDENTIFIER_MAX_LENGTH);
  const stageInstanceIdentifier = boundedString(
    body.stageInstanceIdentifier, IDENTIFIER_MAX_LENGTH,
  );
  const sessionId = boundedString(body.sessionId, IDENTIFIER_MAX_LENGTH);
  const status = knownValue(body.status, RUN_STATUSES);
  const runMode = knownValue(body.runMode, RUN_MODES);
  const lastEventSeq = nonNegativeInteger(body.lastEventSeq);
  if (
    !runId ||
    !workbenchId ||
    !stageInstanceIdentifier
    || !isWorkbenchStageInstanceIdentifier(stageInstanceIdentifier) ||
    !sessionId ||
    !status ||
    !runMode ||
    lastEventSeq == null
  ) {
    return null;
  }
  const projected: WorkbenchRunDetail = {
    runId,
    workbenchId,
    stageInstanceIdentifier,
    sessionId,
    status,
    runMode,
    lastEventSeq,
  };
  if ('earliestRetainedSeq' in body) {
    const earliestRetainedSeq = nonNegativeInteger(body.earliestRetainedSeq);
    if (earliestRetainedSeq == null || earliestRetainedSeq > lastEventSeq + 1) return null;
    projected.earliestRetainedSeq = earliestRetainedSeq;
  }
  if ('createdAt' in body) {
    const createdAt = nonNegativeInteger(body.createdAt);
    const startedAt = nullableNonNegativeInteger(body.startedAt);
    const finishedAt = nullableNonNegativeInteger(body.finishedAt);
    const failureCode = nullableBoundedString(body.failureCode, IDENTIFIER_MAX_LENGTH);
    if (createdAt == null || startedAt === undefined || finishedAt === undefined || failureCode === undefined) {
      return null;
    }
    projected.createdAt = createdAt;
    projected.startedAt = startedAt;
    projected.finishedAt = finishedAt;
    projected.failureCode = failureCode;
  }
  return projected;
}

function historyItemProjection(body: unknown): WorkbenchRunHistoryItem | null {
  const detail = detailProjection(body);
  if (!detail || detail.createdAt == null
    || detail.startedAt === undefined || detail.finishedAt === undefined
    || detail.failureCode === undefined) {
    return null;
  }
  return {
    runId: detail.runId,
    workbenchId: detail.workbenchId,
    stageInstanceIdentifier: detail.stageInstanceIdentifier,
    sessionId: detail.sessionId,
    status: detail.status,
    runMode: detail.runMode,
    lastEventSeq: detail.lastEventSeq,
    ...(detail.earliestRetainedSeq == null
      ? {} : { earliestRetainedSeq: detail.earliestRetainedSeq }),
    createdAt: detail.createdAt,
    startedAt: detail.startedAt,
    finishedAt: detail.finishedAt,
    failureCode: detail.failureCode,
  };
}

function historyPageProjection(body: unknown): WorkbenchRunHistoryPage | null {
  if (!isRecord(body) || !Array.isArray(body.items) || body.items.length > 100) return null;
  const items: WorkbenchRunHistoryItem[] = [];
  for (const candidate of body.items) {
    const item = historyItemProjection(candidate);
    if (!item) return null;
    items.push(item);
  }
  let nextCursor: WorkbenchRunHistoryCursor | null = null;
  if (body.nextCursor !== null) {
    if (!isRecord(body.nextCursor)) return null;
    const createdAt = nonNegativeInteger(body.nextCursor.createdAt);
    const runId = boundedString(body.nextCursor.runId, IDENTIFIER_MAX_LENGTH);
    if (createdAt == null || !runId) return null;
    nextCursor = { createdAt, runId };
  }
  return { items, nextCursor };
}

function historicalEventProjection(body: unknown): WorkbenchRunHistoricalEvent | null {
  if (!isRecord(body)) return null;
  const sequence = positiveInteger(body.sequence);
  const eventType = boundedString(body.eventType, EVENT_TYPE_MAX_LENGTH);
  const payload = typeof body.payload === 'string' && body.payload.length > 0
    && body.payload.length <= EVENT_PAYLOAD_MAX_LENGTH ? body.payload : null;
  return sequence && eventType && payload ? { sequence, eventType, payload } : null;
}

function eventPageProjection(body: unknown): WorkbenchRunEventPage | null {
  if (!isRecord(body) || !Array.isArray(body.events) || body.events.length > EVENT_PAGE_MAX_LIMIT) return null;
  const runId = boundedString(body.runId, IDENTIFIER_MAX_LENGTH);
  const after = nonNegativeInteger(body.after);
  const through = nonNegativeInteger(body.through);
  const lastEventSeq = nonNegativeInteger(body.lastEventSeq);
  const earliestRetainedSeq = nonNegativeInteger(body.earliestRetainedSeq);
  if (!runId || after == null || through == null || lastEventSeq == null
    || earliestRetainedSeq == null || typeof body.hasMore !== 'boolean'
    || through < after || through > lastEventSeq || earliestRetainedSeq > lastEventSeq + 1) {
    return null;
  }
  const events: WorkbenchRunHistoricalEvent[] = [];
  let previous = after;
  for (const candidate of body.events) {
    const event = historicalEventProjection(candidate);
    if (!event || event.sequence <= previous || event.sequence > through) return null;
    events.push(event);
    previous = event.sequence;
  }
  if (events.length > 0 && previous !== through) return null;
  if (events.length === 0 && through !== after) return null;
  return { runId, after, through, lastEventSeq, earliestRetainedSeq, hasMore: body.hasMore, events };
}

function projectArray<T>(
  value: unknown,
  projection: (candidate: unknown) => T | null,
): T[] | null {
  if (!Array.isArray(value) || value.length > 1_000) return null;
  const result: T[] = [];
  for (const candidate of value) {
    const projected = projection(candidate);
    if (!projected) return null;
    result.push(projected);
  }
  return result;
}

function capabilityRuleProjection(body: unknown): WorkbenchRunCapabilityRule | null {
  if (!isRecord(body)) return null;
  const id = boundedString(body.id, IDENTIFIER_MAX_LENGTH);
  const version = boundedString(body.version, IDENTIFIER_MAX_LENGTH);
  const source = boundedString(body.source, IDENTIFIER_MAX_LENGTH);
  const contentHash = boundedString(body.contentHash, HASH_MAX_LENGTH);
  const safeSummary = boundedString(body.safeSummary, SAFE_SUMMARY_MAX_LENGTH);
  return id && version && source && contentHash && typeof body.mandatory === 'boolean' && safeSummary
    ? { id, version, source, contentHash, mandatory: body.mandatory, safeSummary }
    : null;
}

function capabilitySkillProjection(body: unknown): WorkbenchRunCapabilitySkill | null {
  if (!isRecord(body)) return null;
  const id = boundedString(body.id, IDENTIFIER_MAX_LENGTH);
  const version = boundedString(body.version, IDENTIFIER_MAX_LENGTH);
  const source = boundedString(body.source, IDENTIFIER_MAX_LENGTH);
  const packageHash = boundedString(body.packageHash, HASH_MAX_LENGTH);
  const trustTier = boundedString(body.trustTier, IDENTIFIER_MAX_LENGTH);
  return id && version && source && packageHash && trustTier
    ? { id, version, source, packageHash, trustTier }
    : null;
}

function capabilityMcpProjection(body: unknown): WorkbenchRunCapabilityMcpServer | null {
  if (!isRecord(body)) return null;
  const id = boundedString(body.id, IDENTIFIER_MAX_LENGTH);
  const version = boundedString(body.version, IDENTIFIER_MAX_LENGTH);
  const definitionHash = boundedString(body.definitionHash, HASH_MAX_LENGTH);
  const access = boundedString(body.access, IDENTIFIER_MAX_LENGTH);
  const transport = boundedString(body.transport, IDENTIFIER_MAX_LENGTH);
  return id && version && definitionHash && access && transport
    ? { id, version, definitionHash, access, transport }
    : null;
}

function rejectedCapabilityProjection(body: unknown): WorkbenchRunRejectedCapability | null {
  if (!isRecord(body)) return null;
  const id = boundedString(body.id, IDENTIFIER_MAX_LENGTH);
  const reasonCode = boundedString(body.reasonCode, IDENTIFIER_MAX_LENGTH);
  return id && reasonCode ? { id, reasonCode } : null;
}

function runRepositoryProjection(body: unknown): WorkbenchRunRepository | null {
  if (!isRecord(body) || !hasOnlyFields(body, RUN_REPOSITORY_FIELDS)) return null;
  const repositoryKey = logicalRelativePath(body.repositoryKey, RUN_REPOSITORY_KEY_MAX_LENGTH);
  const relativePath = logicalRelativePath(body.relativePath, RUN_REPOSITORY_RELATIVE_PATH_MAX_LENGTH);
  const access = knownValue(body.access, RUN_REPOSITORY_ACCESSES);
  if (!repositoryKey || !relativePath || typeof body.primary !== 'boolean' || !access) return null;
  return { repositoryKey, relativePath, primary: body.primary, access };
}

function repositoryScopeProjection(body: Record<string, unknown>): {
  repositoryScopeHash: string;
  primaryRepositoryKey: string;
  repositories: WorkbenchRunRepository[];
} | null {
  if (hasForbiddenCapabilityScopeField(body)
    || typeof body.repositoryScopeHash !== 'string'
    || !LOWERCASE_SHA_256.test(body.repositoryScopeHash)) {
    return null;
  }
  const primaryRepositoryKey = logicalRelativePath(
    body.primaryRepositoryKey,
    RUN_REPOSITORY_KEY_MAX_LENGTH,
  );
  if (!primaryRepositoryKey || !Array.isArray(body.repositories)
    || body.repositories.length < 1 || body.repositories.length > RUN_REPOSITORY_MAX_COUNT) {
    return null;
  }
  const repositories: WorkbenchRunRepository[] = [];
  const repositoryKeys = new Set<string>();
  let projectedPrimaryKey: string | null = null;
  for (const candidate of body.repositories) {
    const repository = runRepositoryProjection(candidate);
    if (!repository || repositoryKeys.has(repository.repositoryKey)) return null;
    repositoryKeys.add(repository.repositoryKey);
    if (repository.primary) {
      if (projectedPrimaryKey !== null) return null;
      projectedPrimaryKey = repository.repositoryKey;
    }
    repositories.push(repository);
  }
  if (projectedPrimaryKey !== primaryRepositoryKey) return null;
  return {
    repositoryScopeHash: body.repositoryScopeHash,
    primaryRepositoryKey,
    repositories,
  };
}

function capabilityProjection(body: unknown): WorkbenchRunCapability | null {
  if (!isRecord(body)) return null;
  const runId = boundedString(body.runId, IDENTIFIER_MAX_LENGTH);
  const workbenchId = boundedString(body.workbenchId, IDENTIFIER_MAX_LENGTH);
  const stageInstanceIdentifier = boundedString(
    body.stageInstanceIdentifier, IDENTIFIER_MAX_LENGTH,
  );
  const runMode = knownValue(body.runMode, RUN_MODES);
  const createdAt = nonNegativeInteger(body.createdAt);
  const policyVersion = boundedString(body.policyVersion, IDENTIFIER_MAX_LENGTH);
  const profileId = boundedString(body.profileId, IDENTIFIER_MAX_LENGTH);
  const profileVersion = boundedString(body.profileVersion, IDENTIFIER_MAX_LENGTH);
  const profileHash = boundedString(body.profileHash, HASH_MAX_LENGTH);
  const bindingHash = boundedString(body.bindingHash, HASH_MAX_LENGTH);
  const runtimeCompatibility = boundedString(body.runtimeCompatibility, IDENTIFIER_MAX_LENGTH);
  const repositoryScope = repositoryScopeProjection(body);
  const rules = projectArray(body.rules, capabilityRuleProjection);
  const skills = projectArray(body.skills, capabilitySkillProjection);
  const mcpServers = projectArray(body.mcpServers, capabilityMcpProjection);
  const rejected = projectArray(body.rejected, rejectedCapabilityProjection);
  if (!runId || !workbenchId || !stageInstanceIdentifier
    || !isWorkbenchStageInstanceIdentifier(stageInstanceIdentifier)
    || !runMode || createdAt == null
    || !policyVersion || !profileId || !profileVersion || !profileHash
    || !bindingHash || !runtimeCompatibility || !repositoryScope
    || !rules || !skills || !mcpServers || !rejected) {
    return null;
  }
  return {
    runId, workbenchId, stageInstanceIdentifier, runMode, createdAt,
    policyVersion, profileId, profileVersion, profileHash, bindingHash,
    runtimeCompatibility, ...repositoryScope, rules, skills, mcpServers, rejected,
  };
}

function stopProjection(body: unknown): WorkbenchRunStopResponse | null {
  if (!isRecord(body)) return null;
  const runId = boundedString(body.runId, IDENTIFIER_MAX_LENGTH);
  const status = knownValue(body.status, RUN_STATUSES);
  return runId && status ? { runId, status } : null;
}

function responseCode(body: unknown, fallback: string): string {
  if (!isRecord(body) || typeof body.code !== 'string') return fallback;
  return SAFE_SERVER_ERROR_CODES.has(body.code) ? body.code : fallback;
}

function httpError(status: number, body: unknown): WorkbenchRunApiError {
  const detail = ERROR_DETAILS[status] || {
    code: 'WORKBENCH_RUN_REQUEST_FAILED',
    message: 'Workbench Run request failed',
  };
  return new WorkbenchRunApiError(status, responseCode(body, detail.code), detail.message);
}

function unexpectedResponse(status: number): WorkbenchRunApiError {
  return new WorkbenchRunApiError(
    status,
    'WORKBENCH_RUN_UNEXPECTED_RESPONSE',
    'Workbench Run service returned an unexpected response',
  );
}

function invalidProjectionResponse(status: number): WorkbenchRunApiError {
  return new WorkbenchRunApiError(
    status,
    'WORKBENCH_RUN_RESPONSE_INVALID',
    'Workbench Run service returned an invalid response',
  );
}

async function parseResponseBody(response: Response): Promise<unknown> {
  let text: string;
  try {
    text = await response.text();
  } catch {
    throw unexpectedResponse(response.status);
  }
  if (!text) return null;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

function encodedPathSegment(value: string, name: string): string {
  const normalized = boundedString(value, IDENTIFIER_MAX_LENGTH);
  if (!normalized || normalized === '.' || normalized === '..') {
    throw new Error(`${name} path segment is invalid`);
  }
  return encodeURIComponent(normalized);
}

function encodedStageInstanceIdentifier(value: string): string {
  if (typeof value !== 'string' || !STAGE_INSTANCE_IDENTIFIER.test(value)) {
    throw new Error('stageInstanceIdentifier path segment is invalid');
  }
  return encodeURIComponent(value);
}

function scopedRunUrl(workbenchId: string, runId: string): string {
  return (
    '/api/workbenches/' + encodedPathSegment(workbenchId, 'workbenchId') + '/runs/' + encodedPathSegment(runId, 'runId')
  );
}

function normalizedAttachmentPath(value: unknown, name: string, maximum: number): string {
  const normalized = boundedString(value, maximum);
  if (!normalized || normalized.includes('\\') || normalized.startsWith('/')
    || /^[A-Za-z]:/.test(normalized)) {
    throw new Error(`workbench run attachment ${name} is invalid`);
  }
  const segments = normalized.split('/');
  if (segments.some(segment => !segment || segment === '.' || segment === '..')) {
    throw new Error(`workbench run attachment ${name} is invalid`);
  }
  return normalized;
}

export function normalizeWorkbenchRunAttachments(value: unknown): WorkbenchRunAttachment[] {
  if (!Array.isArray(value)) {
    throw new Error('workbench run attachments must be an array');
  }
  if (value.length > ATTACHMENT_MAX_COUNT) {
    throw new Error('workbench run accepts at most eight attachments');
  }
  const normalized: WorkbenchRunAttachment[] = [];
  const references = new Set<string>();
  for (const candidate of value) {
    if (!isRecord(candidate)) {
      throw new Error('workbench run attachment is invalid');
    }
    const type = candidate.type === undefined
      ? 'REPOSITORY_DOCUMENT'
      : boundedString(candidate.type, 64);
    if (type === 'UPLOADED_CONVERSATION') {
      if (candidate.repositoryKey !== undefined || candidate.relativePath !== undefined) {
        throw new Error('uploaded workbench run attachment must not include repository paths');
      }
      const attachmentId = boundedString(candidate.attachmentId, ATTACHMENT_ID_MAX_LENGTH);
      if (!attachmentId || attachmentId === '.' || attachmentId === '..') {
        throw new Error('uploaded workbench run attachment id is invalid');
      }
      if (typeof candidate.contentHash !== 'string'
        || !LOWERCASE_SHA_256.test(candidate.contentHash)) {
        throw new Error('workbench run attachment content hash is invalid');
      }
      const reference = `UPLOADED_CONVERSATION\u0000${attachmentId}`;
      if (references.has(reference)) {
        throw new Error('workbench run attachment references must be unique');
      }
      references.add(reference);
      normalized.push({
        type: 'UPLOADED_CONVERSATION',
        attachmentId,
        contentHash: candidate.contentHash,
      });
      continue;
    }
    if (type !== 'REPOSITORY_DOCUMENT' || candidate.attachmentId !== undefined) {
      throw new Error('workbench run attachment type is invalid');
    }
    const repositoryKey = normalizedAttachmentPath(
      candidate.repositoryKey,
      'repository key',
      ATTACHMENT_REPOSITORY_KEY_MAX_LENGTH,
    );
    const relativePath = normalizedAttachmentPath(
      candidate.relativePath,
      'relative path',
      ATTACHMENT_RELATIVE_PATH_MAX_LENGTH,
    );
    if (typeof candidate.contentHash !== 'string'
      || !LOWERCASE_SHA_256.test(candidate.contentHash)) {
      throw new Error('workbench run attachment content hash is invalid');
    }
    const reference = `REPOSITORY_DOCUMENT\u0000${repositoryKey}\u0000${relativePath}`;
    if (references.has(reference)) {
      throw new Error('workbench run attachment references must be unique');
    }
    references.add(reference);
    normalized.push({
      repositoryKey,
      relativePath,
      contentHash: candidate.contentHash,
    });
  }
  return normalized;
}

function submitPayload(request: SubmitWorkbenchRunRequest): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    message: request.message,
    runMode: request.runMode,
  };
  if (request.attachments !== undefined) {
    payload.attachments = normalizeWorkbenchRunAttachments(request.attachments);
  }
  return payload;
}

function requireExpectedVersion(value: number): void {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error('If-Match workbench version is required');
  }
}

function requireIdempotencyKey(value: string): string {
  if (typeof value !== 'string' || !value.trim() || value.trim().length > 128) {
    throw new Error('Idempotency-Key is required');
  }
  return value.trim();
}

function requireLimit(value: number | undefined, maximum: number, name: string): number | undefined {
  if (value === undefined) return undefined;
  if (!Number.isSafeInteger(value) || value < 1 || value > maximum) {
    throw new Error(`${name} limit is invalid`);
  }
  return value;
}

function historyQuery(filters: WorkbenchRunHistoryQuery): string {
  const params = new URLSearchParams();
  if (filters.stageInstanceIdentifier !== undefined) {
    if (!isWorkbenchStageInstanceIdentifier(
      filters.stageInstanceIdentifier,
    )) {
      throw new Error('stageInstanceIdentifier is invalid');
    }
    params.set('stageInstanceIdentifier', filters.stageInstanceIdentifier);
  }
  const hasCreatedAt = filters.cursorCreatedAt !== undefined;
  const hasRunId = filters.cursorRunId !== undefined;
  if (hasCreatedAt !== hasRunId) throw new Error('history cursor fields must be provided together');
  if (hasCreatedAt) {
    const createdAt = nonNegativeInteger(filters.cursorCreatedAt);
    const runId = boundedString(filters.cursorRunId, IDENTIFIER_MAX_LENGTH);
    if (createdAt == null || !runId) throw new Error('history cursor is invalid');
    params.set('cursorCreatedAt', String(createdAt));
    params.set('cursorRunId', runId);
  }
  const limit = requireLimit(filters.limit, HISTORY_LIST_MAX_LIMIT, 'history');
  if (limit !== undefined) params.set('limit', String(limit));
  const query = params.toString();
  return query ? `?${query}` : '';
}

function eventPageQuery(filters: WorkbenchRunEventPageQuery): string {
  const after = nonNegativeInteger(filters.after);
  if (after == null) throw new Error('event page after cursor is invalid');
  const limit = requireLimit(filters.limit, EVENT_PAGE_MAX_LIMIT, 'event page');
  if (limit === undefined) throw new Error('event page limit is required');
  return `?after=${after}&limit=${limit}`;
}

export function createWorkbenchRunApiClient(
  fetchFn: WorkbenchRunFetch = globalThis.fetch.bind(globalThis),
): WorkbenchRunApiClient {
  async function request<T>(
    url: string,
    init: RequestInit,
    expectedStatus: number,
    projection: (body: unknown) => T | null,
    projectionError: (status: number) => WorkbenchRunApiError = unexpectedResponse,
  ): Promise<T> {
    let response: Response;
    try {
      response = await fetchFn(url, init);
    } catch {
      throw new WorkbenchRunApiError(0, 'WORKBENCH_RUN_NETWORK_ERROR', 'Workbench Run service could not be reached');
    }

    const body = await parseResponseBody(response);
    if (!response.ok) throw httpError(response.status, body);
    if (response.status !== expectedStatus) {
      throw unexpectedResponse(response.status);
    }
    const projected = projection(body);
    if (!projected) throw projectionError(response.status);
    return projected;
  }

  return {
    async getStageConversationMessages(
      workbenchId,
      stageInstanceIdentifier,
      beforeMessageId,
    ): Promise<WorkbenchStageConversationMessages> {
      let url = '/api/workbenches/'
        + encodedPathSegment(workbenchId, 'workbenchId')
        + '/stages/'
        + encodedStageInstanceIdentifier(stageInstanceIdentifier)
        + '/conversation/messages';
      if (beforeMessageId !== undefined) {
        const cursor = positiveInteger(beforeMessageId);
        if (cursor == null) {
          throw new WorkbenchRunApiError(
            0,
            'WORKBENCH_RUN_REQUEST_INVALID',
            'Workbench Run request is invalid',
          );
        }
        url += `?beforeMessageId=${cursor}`;
      }
      return request<WorkbenchStageConversationMessages>(
        url,
        { method: 'GET' },
        200,
        conversationMessagesProjection,
        invalidProjectionResponse,
      );
    },

    async ensureStageConversation(
      workbenchId,
      stageInstanceIdentifier,
      expectedVersion,
    ): Promise<WorkbenchStageConversation> {
      requireExpectedVersion(expectedVersion);
      const url = '/api/workbenches/'
        + encodedPathSegment(workbenchId, 'workbenchId')
        + '/stages/'
        + encodedStageInstanceIdentifier(stageInstanceIdentifier)
        + '/conversation';
      return request<WorkbenchStageConversation>(
        url,
        {
          method: 'POST',
          headers: { 'If-Match': String(expectedVersion) },
        },
        200,
        conversationProjection,
      );
    },

    async restartStageConversation(
      workbenchId,
      stageInstanceIdentifier,
      expectedVersion,
      idempotencyKey,
    ): Promise<WorkbenchStageConversationRestart> {
      requireExpectedVersion(expectedVersion);
      const normalizedIdempotencyKey = requireIdempotencyKey(idempotencyKey);
      const url = '/api/workbenches/'
        + encodedPathSegment(workbenchId, 'workbenchId')
        + '/stages/'
        + encodedStageInstanceIdentifier(stageInstanceIdentifier)
        + '/conversation/restart';
      return request<WorkbenchStageConversationRestart>(
        url,
        {
          method: 'POST',
          headers: {
            'If-Match': String(expectedVersion),
            'Idempotency-Key': normalizedIdempotencyKey,
          },
        },
        200,
        conversationRestartProjection,
        invalidProjectionResponse,
      );
    },

    async submitRun(command): Promise<WorkbenchRunSubmission> {
      requireExpectedVersion(command.expectedVersion);
      const idempotencyKey = requireIdempotencyKey(command.idempotencyKey);
      const url =
        '/api/workbenches/' +
        encodedPathSegment(command.workbenchId, 'workbenchId') +
        '/stages/' +
        encodedStageInstanceIdentifier(command.stageInstanceIdentifier) +
        '/runs';
      return request<WorkbenchRunSubmission>(
        url,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'If-Match': String(command.expectedVersion),
            'Idempotency-Key': idempotencyKey,
          },
          body: JSON.stringify(submitPayload(command.request)),
        },
        202,
        submissionProjection,
      );
    },

    async getRun(workbenchId, runId): Promise<WorkbenchRunDetail> {
      return request<WorkbenchRunDetail>(
        scopedRunUrl(workbenchId, runId),
        {
          method: 'GET',
        },
        200,
        detailProjection,
      );
    },

    async stopRun(workbenchId, runId): Promise<WorkbenchRunStopResponse> {
      return request<WorkbenchRunStopResponse>(
        scopedRunUrl(workbenchId, runId) + '/stop',
        {
          method: 'POST',
        },
        202,
        stopProjection,
      );
    },

    async listRuns(
      workbenchId,
      filters: WorkbenchRunHistoryQuery = {},
    ): Promise<WorkbenchRunHistoryPage> {
      const url = '/api/workbenches/' + encodedPathSegment(workbenchId, 'workbenchId')
        + '/runs' + historyQuery(filters);
      return request<WorkbenchRunHistoryPage>(
        url,
        { method: 'GET' },
        200,
        historyPageProjection,
      );
    },

    async getRunEvents(workbenchId, runId, filters): Promise<WorkbenchRunEventPage> {
      return request<WorkbenchRunEventPage>(
        scopedRunUrl(workbenchId, runId) + '/events-page' + eventPageQuery(filters),
        { method: 'GET' },
        200,
        eventPageProjection,
      );
    },

    async getRunCapability(workbenchId, runId): Promise<WorkbenchRunCapability> {
      return request<WorkbenchRunCapability>(
        scopedRunUrl(workbenchId, runId) + '/capability',
        { method: 'GET' },
        200,
        capabilityProjection,
      );
    },

    eventsUrl(workbenchId, runId): string {
      return scopedRunUrl(workbenchId, runId) + '/events';
    },
  };
}
