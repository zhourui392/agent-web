/**
 * Admin Workbench 独立传输边界：只接受并复制服务端安全投影的白名单字段。
 *
 * @author alex
 * @since 2026-08-01
 */

const IDENTIFIER_MAX_LENGTH = 128;
const REPOSITORY_KEY_MAX_LENGTH = 512;
const TITLE_MAX_LENGTH = 512;
const PERSON_NAME_MAX_LENGTH = 256;
const ENVIRONMENT_MAX_LENGTH = 256;
const FAILURE_CODE_MAX_LENGTH = 128;
const PAGE_LIMIT_MAX = 100;
const SHA_256 = /^[a-f0-9]{64}$/;
const STABLE_CODE = /^[A-Z][A-Z0-9_]*$/;

const WORKBENCH_STATUSES = new Set<AdminWorkbenchStatus>(['ACTIVE', 'ARCHIVED']);
const AGENT_TYPES = new Set<AdminWorkbenchAgentType>(['CODEX', 'CLAUDE', 'NATIVE']);
const STAGE_STATUSES = new Set<AdminWorkbenchStageStatus>([
  'NOT_STARTED',
  'IN_PROGRESS',
  'HUMAN_COMPLETED',
]);
const RUN_STATUSES = new Set<AdminWorkbenchRunStatus>([
  'PENDING',
  'RUNNING',
  'CANCEL_REQUESTED',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'INTERRUPTED',
]);
const RUN_MODES = new Set<AdminWorkbenchRunMode>([
  'DISCUSS_READ_ONLY',
  'MODIFY_WORKSPACE',
]);
const ADMIN_ACTIONS = new Set<AdminWorkbenchRunAction>(['STOP', 'RECONCILE']);
export type AdminWorkbenchFetch = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

export type AdminWorkbenchStatus = 'ACTIVE' | 'ARCHIVED';
export type AdminWorkbenchAgentType = 'CODEX' | 'CLAUDE' | 'NATIVE';
export type AdminWorkbenchStageStatus =
  | 'NOT_STARTED'
  | 'IN_PROGRESS'
  | 'HUMAN_COMPLETED';
export type AdminWorkbenchRunStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'CANCEL_REQUESTED'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'INTERRUPTED';
export type AdminWorkbenchRunMode = 'DISCUSS_READ_ONLY' | 'MODIFY_WORKSPACE';
export type AdminWorkbenchRunAction = 'STOP' | 'RECONCILE';

export interface AdminWorkbenchListItem {
  workbenchId: string;
  ownerId: string;
  ownerName: string;
  title: string;
  status: AdminWorkbenchStatus;
  agentType: AdminWorkbenchAgentType;
  environment: string | null;
  primaryRepositoryKey: string;
  repositoryCount: number;
  activeWriteRunId: string | null;
  createdAt: number;
  updatedAt: number;
  version: number;
}

export interface AdminWorkbenchRepositoryView {
  repositoryKey: string;
  primary: boolean;
}

export interface AdminWorkbenchStageView {
  stageInstanceIdentifier: string;
  definitionIdentifier: string;
  definitionRevision: number;
  sequenceNumber: number;
  status: AdminWorkbenchStageStatus;
  activeRunId: string | null;
  activeRunMode: AdminWorkbenchRunMode | null;
  lastActivityAt: number | null;
  completedAt: number | null;
}

export interface AdminWorkbenchDetail {
  workbenchId: string;
  ownerId: string;
  ownerName: string;
  title: string;
  status: AdminWorkbenchStatus;
  agentType: AdminWorkbenchAgentType;
  environment: string | null;
  primaryRepositoryKey: string;
  repositoryScopeHash: string;
  activeWriteRunId: string | null;
  createdAt: number;
  updatedAt: number;
  version: number;
  repositories: AdminWorkbenchRepositoryView[];
  stages: AdminWorkbenchStageView[];
}

export interface AdminWorkbenchListCursor {
  updatedAt: number;
  workbenchId: string;
}

export interface AdminWorkbenchListPage {
  items: AdminWorkbenchListItem[];
  nextCursor: AdminWorkbenchListCursor | null;
}

export interface AdminWorkbenchListQuery {
  status?: AdminWorkbenchStatus;
  cursorUpdatedAt?: number;
  cursorWorkbenchId?: string;
  limit?: number;
}

export interface AdminWorkbenchRunListItem {
  runId: string;
  workbenchId: string;
  stageInstanceIdentifier: string;
  status: AdminWorkbenchRunStatus;
  runMode: AdminWorkbenchRunMode;
  lastEventSeq: number;
  createdAt: number;
  startedAt: number | null;
  cancelRequestedAt: number | null;
  finishedAt: number | null;
  failureCode: string | null;
}

export interface AdminWorkbenchRunDetail extends AdminWorkbenchRunListItem {
  exitCode: number | null;
  repositoryScopeHash: string;
  capabilitySnapshotHash: string;
  promptHash: string;
  runtimeHandlePresent: boolean;
}

export interface AdminWorkbenchRunListCursor {
  createdAt: number;
  runId: string;
}

export interface AdminWorkbenchRunListPage {
  items: AdminWorkbenchRunListItem[];
  nextCursor: AdminWorkbenchRunListCursor | null;
}

export interface AdminWorkbenchRunListQuery {
  status?: AdminWorkbenchRunStatus;
  cursorCreatedAt?: number;
  cursorRunId?: string;
  limit?: number;
}

export interface AdminWorkbenchRunActionResult {
  workbenchId: string;
  runId: string;
  action: AdminWorkbenchRunAction;
  outcome: string;
  runStatus: AdminWorkbenchRunStatus | null;
  acceptedAt: number;
}

export interface AdminWorkbenchApiClient {
  listWorkbenches(query?: AdminWorkbenchListQuery): Promise<AdminWorkbenchListPage>;
  getWorkbench(workbenchId: string): Promise<AdminWorkbenchDetail>;
  listRuns(
    workbenchId: string,
    query?: AdminWorkbenchRunListQuery,
  ): Promise<AdminWorkbenchRunListPage>;
  getRun(workbenchId: string, runId: string): Promise<AdminWorkbenchRunDetail>;
  stopRun(workbenchId: string, runId: string): Promise<AdminWorkbenchRunActionResult>;
  reconcileRun(workbenchId: string, runId: string): Promise<AdminWorkbenchRunActionResult>;
}

export class AdminWorkbenchApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'AdminWorkbenchApiError';
    this.status = status;
    this.code = code;
  }
}

function invalid(): never {
  throw new Error('Admin Workbench response is invalid');
}

function record(value: unknown): Record<string, unknown> {
  if (value == null || typeof value !== 'object' || Array.isArray(value)) return invalid();
  return value as Record<string, unknown>;
}

function stringValue(value: unknown, maximum: number): string {
  if (typeof value !== 'string' || value.length < 1 || value.length > maximum) return invalid();
  return value;
}

function optionalString(value: unknown, maximum: number): string | null {
  return value == null ? null : stringValue(value, maximum);
}

function identifier(value: unknown): string {
  const parsed = stringValue(value, IDENTIFIER_MAX_LENGTH);
  if (parsed.trim() !== parsed || parsed.length === 0) return invalid();
  return parsed;
}

function repositoryKey(value: unknown): string {
  const parsed = stringValue(value, REPOSITORY_KEY_MAX_LENGTH);
  if (parsed.startsWith('/') || parsed.startsWith('\\') || /^[A-Za-z]:/.test(parsed)
    || parsed.includes('\\') || parsed.split('/').some(part => !part || part === '.' || part === '..')) {
    return invalid();
  }
  return parsed;
}

function integer(value: unknown, minimum = 0): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) return invalid();
  return value;
}

function nullableInteger(value: unknown, minimum = 0): number | null {
  return value == null ? null : integer(value, minimum);
}

function booleanValue(value: unknown): boolean {
  if (typeof value !== 'boolean') return invalid();
  return value;
}

function enumValue<T extends string>(value: unknown, values: Set<T>): T {
  if (typeof value !== 'string' || !values.has(value as T)) return invalid();
  return value as T;
}

function hash(value: unknown): string {
  if (typeof value !== 'string' || !SHA_256.test(value)) return invalid();
  return value;
}

function failureCode(value: unknown): string | null {
  if (value == null) return null;
  const parsed = stringValue(value, FAILURE_CODE_MAX_LENGTH);
  if (!STABLE_CODE.test(parsed)) return invalid();
  return parsed;
}

function pageLimit(value: number | undefined): number | undefined {
  if (value == null) return undefined;
  if (!Number.isInteger(value) || value < 1 || value > PAGE_LIMIT_MAX) return invalid();
  return value;
}

function parseWorkbenchListItem(value: unknown): AdminWorkbenchListItem {
  const input = record(value);
  return {
    workbenchId: identifier(input.workbenchId),
    ownerId: identifier(input.ownerId),
    ownerName: stringValue(input.ownerName, PERSON_NAME_MAX_LENGTH),
    title: stringValue(input.title, TITLE_MAX_LENGTH),
    status: enumValue(input.status, WORKBENCH_STATUSES),
    agentType: enumValue(input.agentType, AGENT_TYPES),
    environment: optionalString(input.environment, ENVIRONMENT_MAX_LENGTH),
    primaryRepositoryKey: repositoryKey(input.primaryRepositoryKey),
    repositoryCount: integer(input.repositoryCount),
    activeWriteRunId: input.activeWriteRunId == null ? null : identifier(input.activeWriteRunId),
    createdAt: integer(input.createdAt),
    updatedAt: integer(input.updatedAt),
    version: integer(input.version),
  };
}

function parseWorkbenchCursor(value: unknown): AdminWorkbenchListCursor | null {
  if (value == null) return null;
  const input = record(value);
  return {
    updatedAt: integer(input.updatedAt),
    workbenchId: identifier(input.workbenchId),
  };
}

function parseWorkbenchPage(value: unknown): AdminWorkbenchListPage {
  const input = record(value);
  if (!Array.isArray(input.items) || input.items.length > PAGE_LIMIT_MAX) return invalid();
  return {
    items: input.items.map(parseWorkbenchListItem),
    nextCursor: parseWorkbenchCursor(input.nextCursor),
  };
}

function parseRepository(value: unknown): AdminWorkbenchRepositoryView {
  const input = record(value);
  return {
    repositoryKey: repositoryKey(input.repositoryKey),
    primary: booleanValue(input.primary),
  };
}

function parseStage(value: unknown): AdminWorkbenchStageView {
  const input = record(value);
  const activeRunId = input.activeRunId == null ? null : identifier(input.activeRunId);
  const activeRunMode = input.activeRunMode == null
    ? null
    : enumValue(input.activeRunMode, RUN_MODES);
  if ((activeRunId == null) !== (activeRunMode == null)) return invalid();
  return {
    stageInstanceIdentifier: identifier(input.stageInstanceIdentifier),
    definitionIdentifier: identifier(input.definitionIdentifier),
    definitionRevision: integer(input.definitionRevision, 1),
    sequenceNumber: integer(input.sequenceNumber, 1),
    status: enumValue(input.status, STAGE_STATUSES),
    activeRunId,
    activeRunMode,
    lastActivityAt: nullableInteger(input.lastActivityAt),
    completedAt: nullableInteger(input.completedAt),
  };
}

function parseWorkbenchDetail(value: unknown): AdminWorkbenchDetail {
  const input = record(value);
  if (!Array.isArray(input.repositories) || !Array.isArray(input.stages)) return invalid();
  const repositories = input.repositories.map(parseRepository);
  const stages = input.stages.map(parseStage);
  const primaryRepositoryKey = repositoryKey(input.primaryRepositoryKey);
  if (repositories.length === 0 || repositories.length > PAGE_LIMIT_MAX
    || new Set(repositories.map(item => item.repositoryKey)).size !== repositories.length
    || repositories.filter(item => item.primary).length !== 1
    || !repositories.some(item => item.primary && item.repositoryKey === primaryRepositoryKey)
    || stages.length === 0
    || new Set(stages.map(item => item.stageInstanceIdentifier)).size !== stages.length
    || new Set(stages.map(item => item.sequenceNumber)).size !== stages.length) {
    return invalid();
  }
  return {
    workbenchId: identifier(input.workbenchId),
    ownerId: identifier(input.ownerId),
    ownerName: stringValue(input.ownerName, PERSON_NAME_MAX_LENGTH),
    title: stringValue(input.title, TITLE_MAX_LENGTH),
    status: enumValue(input.status, WORKBENCH_STATUSES),
    agentType: enumValue(input.agentType, AGENT_TYPES),
    environment: optionalString(input.environment, ENVIRONMENT_MAX_LENGTH),
    primaryRepositoryKey,
    repositoryScopeHash: hash(input.repositoryScopeHash),
    activeWriteRunId: input.activeWriteRunId == null ? null : identifier(input.activeWriteRunId),
    createdAt: integer(input.createdAt),
    updatedAt: integer(input.updatedAt),
    version: integer(input.version),
    repositories,
    stages: stages.sort((left, right) => left.sequenceNumber - right.sequenceNumber),
  };
}

function parseRunListItem(value: unknown): AdminWorkbenchRunListItem {
  const input = record(value);
  return {
    runId: identifier(input.runId),
    workbenchId: identifier(input.workbenchId),
    stageInstanceIdentifier: identifier(input.stageInstanceIdentifier),
    status: enumValue(input.status, RUN_STATUSES),
    runMode: enumValue(input.runMode, RUN_MODES),
    lastEventSeq: integer(input.lastEventSeq),
    createdAt: integer(input.createdAt),
    startedAt: nullableInteger(input.startedAt),
    cancelRequestedAt: nullableInteger(input.cancelRequestedAt),
    finishedAt: nullableInteger(input.finishedAt),
    failureCode: failureCode(input.failureCode),
  };
}

function parseRunCursor(value: unknown): AdminWorkbenchRunListCursor | null {
  if (value == null) return null;
  const input = record(value);
  return {
    createdAt: integer(input.createdAt),
    runId: identifier(input.runId),
  };
}

function parseRunPage(value: unknown): AdminWorkbenchRunListPage {
  const input = record(value);
  if (!Array.isArray(input.items) || input.items.length > PAGE_LIMIT_MAX) return invalid();
  return {
    items: input.items.map(parseRunListItem),
    nextCursor: parseRunCursor(input.nextCursor),
  };
}

function parseRunDetail(value: unknown): AdminWorkbenchRunDetail {
  const input = record(value);
  return {
    ...parseRunListItem(input),
    exitCode: nullableInteger(input.exitCode, -2147483648),
    repositoryScopeHash: hash(input.repositoryScopeHash),
    capabilitySnapshotHash: hash(input.capabilitySnapshotHash),
    promptHash: hash(input.promptHash),
    runtimeHandlePresent: booleanValue(input.runtimeHandlePresent),
  };
}

function parseActionResult(value: unknown): AdminWorkbenchRunActionResult {
  const input = record(value);
  const outcome = stringValue(input.outcome, 64);
  if (!STABLE_CODE.test(outcome)) return invalid();
  return {
    workbenchId: identifier(input.workbenchId),
    runId: identifier(input.runId),
    action: enumValue(input.action, ADMIN_ACTIONS),
    outcome,
    runStatus: input.runStatus == null ? null : enumValue(input.runStatus, RUN_STATUSES),
    acceptedAt: integer(input.acceptedAt),
  };
}

async function responseBody(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) return invalid();
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return invalid();
  }
}

function safeError(status: number): AdminWorkbenchApiError {
  const details: Record<number, [string, string]> = {
    400: ['WORKBENCH_ADMIN_REQUEST_INVALID', 'Admin Workbench request is invalid'],
    401: ['WORKBENCH_ADMIN_UNAUTHORIZED', 'Administrator login is required'],
    403: ['WORKBENCH_ADMIN_FORBIDDEN', 'Administrator role is required'],
    404: ['WORKBENCH_RUN_NOT_FOUND', 'Admin Workbench resource was not found'],
    503: ['WORKBENCH_ADMIN_RECONCILIATION_FAILED', 'Run reconciliation is unavailable'],
  };
  const detail = details[status] ?? ['WORKBENCH_ADMIN_INTERNAL_ERROR', 'Admin Workbench request failed'];
  return new AdminWorkbenchApiError(status, detail[0], detail[1]);
}

async function request(
  fetchImpl: AdminWorkbenchFetch,
  url: string,
  init: RequestInit,
): Promise<unknown> {
  let response: Response;
  try {
    response = await fetchImpl(url, init);
  } catch {
    throw new AdminWorkbenchApiError(
      0,
      'WORKBENCH_ADMIN_NETWORK_ERROR',
      'Admin Workbench service is unreachable',
    );
  }
  if (!response.ok) throw safeError(response.status);
  return responseBody(response);
}

function queryString(values: Array<[string, string | number | undefined]>): string {
  const query = new URLSearchParams();
  for (const [name, value] of values) {
    if (value != null) query.set(name, String(value));
  }
  const encoded = query.toString();
  return encoded ? `?${encoded}` : '';
}

function requireCursorPair(left: unknown, right: unknown): void {
  if ((left == null) !== (right == null)) invalid();
}

export function createAdminWorkbenchApiClient(
  fetchImpl: AdminWorkbenchFetch = globalThis.fetch.bind(globalThis),
): AdminWorkbenchApiClient {
  return {
    async listWorkbenches(query: AdminWorkbenchListQuery = {}) {
      requireCursorPair(query.cursorUpdatedAt, query.cursorWorkbenchId);
      const status = query.status == null ? undefined : enumValue(query.status, WORKBENCH_STATUSES);
      const cursorUpdatedAt = query.cursorUpdatedAt == null
        ? undefined
        : integer(query.cursorUpdatedAt);
      const cursorWorkbenchId = query.cursorWorkbenchId == null
        ? undefined
        : identifier(query.cursorWorkbenchId);
      const limit = pageLimit(query.limit);
      const payload = await request(fetchImpl, `/api/admin/workbenches${queryString([
        ['status', status],
        ['cursorUpdatedAt', cursorUpdatedAt],
        ['cursorWorkbenchId', cursorWorkbenchId],
        ['limit', limit],
      ])}`, { method: 'GET' });
      return parseWorkbenchPage(payload);
    },

    async getWorkbench(workbenchId: string) {
      const expected = identifier(workbenchId);
      const payload = await request(
        fetchImpl,
        `/api/admin/workbenches/${encodeURIComponent(expected)}`,
        { method: 'GET' },
      );
      const parsed = parseWorkbenchDetail(payload);
      if (parsed.workbenchId !== expected) return invalid();
      return parsed;
    },

    async listRuns(workbenchId: string, query: AdminWorkbenchRunListQuery = {}) {
      const expectedWorkbench = identifier(workbenchId);
      requireCursorPair(query.cursorCreatedAt, query.cursorRunId);
      const status = query.status == null ? undefined : enumValue(query.status, RUN_STATUSES);
      const cursorCreatedAt = query.cursorCreatedAt == null
        ? undefined
        : integer(query.cursorCreatedAt);
      const cursorRunId = query.cursorRunId == null ? undefined : identifier(query.cursorRunId);
      const limit = pageLimit(query.limit);
      const payload = await request(
        fetchImpl,
        `/api/admin/workbenches/${encodeURIComponent(expectedWorkbench)}/runs${queryString([
          ['status', status],
          ['cursorCreatedAt', cursorCreatedAt],
          ['cursorRunId', cursorRunId],
          ['limit', limit],
        ])}`,
        { method: 'GET' },
      );
      const parsed = parseRunPage(payload);
      if (parsed.items.some(item => item.workbenchId !== expectedWorkbench)) return invalid();
      return parsed;
    },

    async getRun(workbenchId: string, runId: string) {
      const expectedWorkbench = identifier(workbenchId);
      const expectedRun = identifier(runId);
      const payload = await request(
        fetchImpl,
        `/api/admin/workbenches/${encodeURIComponent(expectedWorkbench)}/runs/${encodeURIComponent(expectedRun)}`,
        { method: 'GET' },
      );
      const parsed = parseRunDetail(payload);
      if (parsed.workbenchId !== expectedWorkbench || parsed.runId !== expectedRun) return invalid();
      return parsed;
    },

    async stopRun(workbenchId: string, runId: string) {
      const expectedWorkbench = identifier(workbenchId);
      const expectedRun = identifier(runId);
      const payload = await request(
        fetchImpl,
        `/api/admin/workbenches/${encodeURIComponent(expectedWorkbench)}/runs/${encodeURIComponent(expectedRun)}/stop`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: '{}',
        },
      );
      const parsed = parseActionResult(payload);
      if (parsed.workbenchId !== expectedWorkbench || parsed.runId !== expectedRun
        || parsed.action !== 'STOP') return invalid();
      return parsed;
    },

    async reconcileRun(workbenchId: string, runId: string) {
      const expectedWorkbench = identifier(workbenchId);
      const expectedRun = identifier(runId);
      const payload = await request(
        fetchImpl,
        `/api/admin/workbenches/${encodeURIComponent(expectedWorkbench)}/runs/${encodeURIComponent(expectedRun)}/reconcile`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: '{}',
        },
      );
      const parsed = parseActionResult(payload);
      if (parsed.workbenchId !== expectedWorkbench || parsed.runId !== expectedRun
        || parsed.action !== 'RECONCILE') return invalid();
      return parsed;
    },
  };
}
