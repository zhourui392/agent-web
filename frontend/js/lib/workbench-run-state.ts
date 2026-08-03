/**
 * Workbench Run 的用户隔离恢复标记与有界 SSE 事件投影。
 *
 * <p>本模块只处理结构化身份和服务端安全事件合同，不依赖 Vue、DOM 或绝对工作目录。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
import {
  isWorkbenchPhase,
  type WorkbenchPhase,
} from './workbench-state.js';

const MARKER_SCHEMA_VERSION = 'workbench-run-marker@1';
const EVENT_SCHEMA_VERSION = 'workbench-run-event@1';

export const WORKBENCH_RUN_LIMITS = {
  blocks: 200,
  staleDocuments: 100,
  testProgress: 100,
  operations: 50,
  genericSummaryChars: 4000,
  commandSummaryChars: 1024,
  outputSummaryChars: 1024,
  textChars: 32 * 1024,
  eventPayloadChars: 128 * 1024,
  operationTargetChars: 8000,
} as const;

const RUN_STATUSES = new Set<WorkbenchRunStatus>([
  'PENDING',
  'RUNNING',
  'CANCEL_REQUESTED',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'INTERRUPTED',
]);

const TERMINAL_STATUSES = new Set<WorkbenchTerminalStatus>([
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'INTERRUPTED',
]);

const RUN_MODES = new Set<WorkbenchRunMode>([
  'DISCUSS_READ_ONLY',
  'MODIFY_WORKSPACE',
]);

export type WorkbenchRunStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'CANCEL_REQUESTED'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'INTERRUPTED';

export type WorkbenchTerminalStatus =
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'INTERRUPTED';

export type WorkbenchRunMode = 'DISCUSS_READ_ONLY' | 'MODIFY_WORKSPACE';

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface WorkbenchRunMarkerIdentity {
  userId: string;
  workbenchId: string;
  phase: WorkbenchPhase;
  conversationGeneration: number;
}

export interface WorkbenchRunMarker extends WorkbenchRunMarkerIdentity {
  schemaVersion: typeof MARKER_SCHEMA_VERSION;
  runId: string;
  lastAppliedEventSeq: number;
}

export interface WorkbenchRunMarkerStore {
  load(): WorkbenchRunMarker | null;
  save(runId: string, lastAppliedEventSeq: number): void;
  clear(): void;
}

export interface WorkbenchRunContext {
  workbenchId: string;
  phase: WorkbenchPhase;
  runId: string;
}

export interface WorkbenchRunSseEvent {
  id: string | number;
  type: string;
  data: unknown;
}

export type WorkbenchRunBlockKind =
  | 'agent_chunk'
  | 'tool'
  | 'generic';

/**
 * 对话时间线的白名单投影；不保留完整环境、stderr 或绝对路径。
 */
export interface WorkbenchRunBlock {
  kind: WorkbenchRunBlockKind;
  eventId: number;
  occurredAt: number;
  content?: string;
  tool?: string;
  callId?: string;
  status?: string;
  durationMs?: number;
  repositoryKey?: string;
  commandClass?: string;
  exitCode?: number;
  commandSummary?: string;
  outputSummary?: string;
  eventType?: string;
  summary?: string;
}

export interface WorkbenchStaleDocument {
  repositoryKey: string;
  path: string;
  changeType: string;
  contentVersion: string;
  stale: true;
  eventId: number;
  occurredAt: number;
}

export interface WorkbenchTestProgress {
  repositoryKey: string;
  suite: string;
  status: string;
  summary: string;
  eventId: number;
  occurredAt: number;
}

export interface WorkbenchOperationProposal {
  operationId: string;
  type: string;
  target: unknown;
  summary: string;
  eventId: number;
  occurredAt: number;
}

export interface WorkbenchRunTerminal {
  status: WorkbenchTerminalStatus;
  failureCode: string | null;
  publicMessage: string | null;
  eventId: number;
  occurredAt: number;
}

export interface WorkbenchRunState {
  context: WorkbenchRunContext;
  status: WorkbenchRunStatus | null;
  runMode: WorkbenchRunMode | null;
  lastAppliedEventSeq: number;
  blocks: ReadonlyArray<WorkbenchRunBlock>;
  staleDocuments: ReadonlyArray<WorkbenchStaleDocument>;
  testProgress: ReadonlyArray<WorkbenchTestProgress>;
  operations: ReadonlyArray<WorkbenchOperationProposal>;
  terminal: WorkbenchRunTerminal | null;
}

interface WorkbenchRunEnvelope {
  occurredAt: number;
  data: Record<string, unknown>;
}

function boundedIdentifier(value: unknown, name: string, maximum: number): string {
  if (typeof value !== 'string' || !value.trim()) {
    throw new IllegalStateError(`${name} must not be blank`);
  }
  const normalized = value.trim();
  if (normalized.length > maximum || containsControlCharacter(normalized)) {
    throw new IllegalStateError(`${name} is invalid`);
  }
  return normalized;
}

function optionalText(value: unknown, maximum: number): string | null | undefined {
  if (value == null) return null;
  if (typeof value !== 'string' || containsDisallowedControlCharacter(value)) {
    return undefined;
  }
  return truncate(value, maximum);
}

function requiredText(value: unknown, maximum: number): string | null {
  if (typeof value !== 'string' || containsDisallowedControlCharacter(value)) {
    return null;
  }
  return truncate(value, maximum);
}

function finiteNonNegativeInteger(value: unknown): number | null {
  return typeof value === 'number'
    && Number.isSafeInteger(value)
    && value >= 0
    ? value
    : null;
}

function normalizeMarkerIdentity(
  identity: WorkbenchRunMarkerIdentity,
): WorkbenchRunMarkerIdentity {
  if (!identity) throw new IllegalStateError('marker identity is required');
  const userId = boundedIdentifier(identity.userId, 'authenticated user id', 128);
  const workbenchId = boundedIdentifier(identity.workbenchId, 'workbench id', 128);
  if (!isWorkbenchPhase(identity.phase)) {
    throw new IllegalStateError('workbench phase is invalid');
  }
  const generation = finiteNonNegativeInteger(identity.conversationGeneration);
  if (generation == null) {
    throw new IllegalStateError('conversation generation is invalid');
  }
  return {
    userId,
    workbenchId,
    phase: identity.phase,
    conversationGeneration: generation,
  };
}

function normalizeRunContext(context: WorkbenchRunContext): WorkbenchRunContext {
  if (!context) throw new IllegalStateError('run context is required');
  const workbenchId = boundedIdentifier(context.workbenchId, 'workbench id', 128);
  const runId = boundedIdentifier(context.runId, 'run id', 128);
  if (!isWorkbenchPhase(context.phase)) {
    throw new IllegalStateError('workbench phase is invalid');
  }
  return { workbenchId, phase: context.phase, runId };
}

function encodeStoragePart(value: string | number): string {
  return encodeURIComponent(String(value));
}

export function workbenchRunMarkerStorageKey(
  identity: WorkbenchRunMarkerIdentity,
): string {
  const normalized = normalizeMarkerIdentity(identity);
  return [
    'agent-web:workbench-run',
    encodeStoragePart(normalized.userId),
    encodeStoragePart(normalized.workbenchId),
    encodeStoragePart(normalized.phase),
    encodeStoragePart(normalized.conversationGeneration),
  ].join(':');
}

function markerMatches(
  parsed: Record<string, unknown>,
  expected: WorkbenchRunMarkerIdentity,
): boolean {
  return parsed.schemaVersion === MARKER_SCHEMA_VERSION
    && parsed.userId === expected.userId
    && parsed.workbenchId === expected.workbenchId
    && parsed.phase === expected.phase
    && parsed.conversationGeneration === expected.conversationGeneration;
}

function parseMarker(
  raw: string | null,
  expected: WorkbenchRunMarkerIdentity,
): WorkbenchRunMarker | null {
  try {
    const parsed = raw ? JSON.parse(raw) as unknown : null;
    if (!isRecord(parsed) || !markerMatches(parsed, expected)) return null;
    const runId = boundedIdentifier(parsed.runId, 'run id', 128);
    const cursor = finiteNonNegativeInteger(parsed.lastAppliedEventSeq);
    if (cursor == null) return null;
    return {
      schemaVersion: MARKER_SCHEMA_VERSION,
      ...expected,
      runId,
      lastAppliedEventSeq: cursor,
    };
  } catch {
    return null;
  }
}

export function createWorkbenchRunMarkerStore(
  storage: StorageLike,
  identity: WorkbenchRunMarkerIdentity,
): WorkbenchRunMarkerStore {
  if (!storage) throw new IllegalStateError('marker storage is required');
  const normalized = normalizeMarkerIdentity(identity);
  const key = workbenchRunMarkerStorageKey(normalized);

  return {
    load(): WorkbenchRunMarker | null {
      let raw: string | null;
      try {
        raw = storage.getItem(key);
      } catch {
        return null;
      }
      if (raw == null) return null;
      const marker = parseMarker(raw, normalized);
      if (!marker) {
        try {
          storage.removeItem(key);
        } catch {
          // 无法清理损坏标记时仍按没有可恢复标记处理。
        }
      }
      return marker;
    },

    save(runId: string, lastAppliedEventSeq: number): void {
      const marker: WorkbenchRunMarker = {
        schemaVersion: MARKER_SCHEMA_VERSION,
        ...normalized,
        runId: boundedIdentifier(runId, 'run id', 128),
        lastAppliedEventSeq: requireCursor(lastAppliedEventSeq),
      };
      storage.setItem(key, JSON.stringify(marker));
    },

    clear(): void {
      storage.removeItem(key);
    },
  };
}

export function createWorkbenchRunState(
  context: WorkbenchRunContext,
): WorkbenchRunState {
  return {
    context: normalizeRunContext(context),
    status: null,
    runMode: null,
    lastAppliedEventSeq: 0,
    blocks: [],
    staleDocuments: [],
    testProgress: [],
    operations: [],
    terminal: null,
  };
}

export function applyWorkbenchRunEvent(
  state: WorkbenchRunState,
  event: WorkbenchRunSseEvent,
  context: WorkbenchRunContext,
): WorkbenchRunState {
  if (!state || state.terminal || !event) return state;

  let normalizedContext: WorkbenchRunContext;
  try {
    normalizedContext = normalizeRunContext(context);
  } catch {
    return state;
  }
  if (!sameContext(state.context, normalizedContext)) return state;

  const sequence = eventSequence(event.id);
  if (sequence == null || sequence <= state.lastAppliedEventSeq) return state;
  const eventType = safeEventType(event.type);
  if (!eventType) return state;
  const envelope = parseEnvelope(event.data, normalizedContext);
  if (!envelope) return state;

  switch (eventType) {
    case 'run_status':
      return reduceRunStatus(state, envelope, sequence);
    case 'agent_chunk':
      return reduceAgentChunk(state, envelope, sequence);
    case 'tool_started':
    case 'tool_finished':
      return reduceTool(state, envelope, sequence, eventType);
    case 'command_started':
    case 'command_finished':
      return reduceCommand(state, envelope, sequence, eventType);
    case 'file_changed':
      return reduceFileChanged(state, envelope, sequence);
    case 'test_progress':
      return reduceTestProgress(state, envelope, sequence);
    case 'operation_proposed':
      return reduceOperationProposed(state, envelope, sequence);
    case 'terminal':
      return reduceTerminal(state, envelope, sequence);
    default:
      return state;
  }
}

function reduceRunStatus(
  state: WorkbenchRunState,
  envelope: WorkbenchRunEnvelope,
  sequence: number,
): WorkbenchRunState {
  const status = knownValue(envelope.data.status, RUN_STATUSES);
  if (!status) return state;
  const runModeValue = envelope.data.runMode;
  const runMode = runModeValue == null
    ? state.runMode
    : knownValue(runModeValue, RUN_MODES);
  if (runModeValue != null && !runMode) return state;
  return {
    ...state,
    status,
    runMode,
    lastAppliedEventSeq: sequence,
  };
}

function reduceAgentChunk(
  state: WorkbenchRunState,
  envelope: WorkbenchRunEnvelope,
  sequence: number,
): WorkbenchRunState {
  const content = requiredText(envelope.data.content, WORKBENCH_RUN_LIMITS.textChars);
  if (content == null) return state;
  const last = state.blocks.length > 0
    ? state.blocks[state.blocks.length - 1] : null;
  if (last && last.kind === 'agent_chunk') {
    const merged: WorkbenchRunBlock = {
      ...last,
      content: truncate(
        (last.content || '') + content,
        WORKBENCH_RUN_LIMITS.textChars,
      ),
      eventId: sequence,
    };
    return {
      ...state,
      lastAppliedEventSeq: sequence,
      blocks: [...state.blocks.slice(0, -1), merged],
    };
  }
  return withBlock(state, sequence, {
    kind: 'agent_chunk',
    eventId: sequence,
    occurredAt: envelope.occurredAt,
    content,
  });
}

function reduceTool(
  state: WorkbenchRunState,
  envelope: WorkbenchRunEnvelope,
  sequence: number,
  kind: 'tool_started' | 'tool_finished',
): WorkbenchRunState {
  const tool = optionalIdentifier(envelope.data.tool, 256);
  const callId = optionalIdentifier(envelope.data.callId, 256);
  const status = optionalIdentifier(envelope.data.status, 80, true);
  if (!tool || !callId || envelope.data.status != null && !status) return state;
  const durationMs = envelope.data.durationMs == null
    ? undefined
    : finiteNonNegativeInteger(envelope.data.durationMs);
  if (envelope.data.durationMs != null && durationMs == null) return state;

  if (kind === 'tool_started') {
    return withBlock(state, sequence, {
      kind: 'tool',
      eventId: sequence,
      occurredAt: envelope.occurredAt,
      tool,
      callId,
      status: status || 'RUNNING',
      durationMs: undefined,
    });
  }
  // tool_finished: merge into the existing tool block matched by callId
  const blocks = [...state.blocks];
  for (let i = blocks.length - 1; i >= 0; i--) {
    const b = blocks[i];
    if (b.kind === 'tool' && b.callId === callId) {
      blocks[i] = {
        ...b,
        status: status || b.status,
        durationMs: durationMs == null ? b.durationMs : durationMs,
      };
      return { ...state, lastAppliedEventSeq: sequence, blocks };
    }
  }
  return withBlock(state, sequence, {
    kind: 'tool',
    eventId: sequence,
    occurredAt: envelope.occurredAt,
    tool,
    callId,
    status: status || undefined,
    durationMs: durationMs == null ? undefined : durationMs,
  });
}

function reduceCommand(
  state: WorkbenchRunState,
  envelope: WorkbenchRunEnvelope,
  sequence: number,
  kind: 'command_started' | 'command_finished',
): WorkbenchRunState {
  const repositoryKey = optionalIdentifier(envelope.data.repositoryKey, 256);
  const commandClass = optionalIdentifier(envelope.data.commandClass, 128);
  if (!repositoryKey || !commandClass) return state;
  const status = optionalIdentifier(envelope.data.status, 80, true);
  const commandSummary = optionalText(
    envelope.data.commandSummary,
    WORKBENCH_RUN_LIMITS.commandSummaryChars,
  );
  const outputSummary = optionalText(
    envelope.data.outputSummary,
    WORKBENCH_RUN_LIMITS.outputSummaryChars,
  );
  if (envelope.data.status != null && !status
    || commandSummary === undefined || outputSummary === undefined) return state;
  const exitCode = envelope.data.exitCode == null
    ? undefined
    : safeInteger(envelope.data.exitCode);
  if (envelope.data.exitCode != null && exitCode == null) return state;

  const blocks = [...state.blocks];
  if (kind === 'command_started') {
    // Merge into the most recent tool block that has no commandClass yet
    for (let i = blocks.length - 1; i >= 0; i--) {
      const b = blocks[i];
      if (b.kind === 'tool' && !b.commandClass) {
        blocks[i] = {
          ...b,
          repositoryKey,
          commandClass,
          commandSummary: commandSummary || b.commandSummary,
          status: status || b.status,
        };
        return { ...state, lastAppliedEventSeq: sequence, blocks };
      }
    }
    // No matching tool block — create standalone
    return withBlock(state, sequence, {
      kind: 'tool',
      eventId: sequence,
      occurredAt: envelope.occurredAt,
      repositoryKey,
      commandClass,
      status: status || undefined,
      commandSummary: commandSummary || undefined,
    });
  }
  // command_finished: merge into the matching tool block
  for (let i = blocks.length - 1; i >= 0; i--) {
    const b = blocks[i];
    if (b.kind === 'tool' && b.repositoryKey === repositoryKey
      && b.commandClass === commandClass && b.status !== 'SUCCEEDED' && b.status !== 'FAILED') {
      blocks[i] = {
        ...b,
        status: status || b.status,
        exitCode: exitCode == null ? b.exitCode : exitCode,
        outputSummary: outputSummary || b.outputSummary,
      };
      return { ...state, lastAppliedEventSeq: sequence, blocks };
    }
  }
  return state;
}

function reduceFileChanged(
  state: WorkbenchRunState,
  envelope: WorkbenchRunEnvelope,
  sequence: number,
): WorkbenchRunState {
  const repositoryKey = optionalIdentifier(envelope.data.repositoryKey, 256);
  const path = relativePath(envelope.data.path);
  const changeType = optionalIdentifier(envelope.data.changeType, 80);
  const contentVersion = optionalIdentifier(envelope.data.contentVersion, 256);
  if (!repositoryKey || !path || !changeType || !contentVersion) return state;
  const changed: WorkbenchStaleDocument = {
    repositoryKey,
    path,
    changeType,
    contentVersion,
    stale: true,
    eventId: sequence,
    occurredAt: envelope.occurredAt,
  };
  return {
    ...state,
    lastAppliedEventSeq: sequence,
    staleDocuments: upsertBounded(
      state.staleDocuments,
      changed,
      item => `${item.repositoryKey}\n${item.path}`,
      WORKBENCH_RUN_LIMITS.staleDocuments,
    ),
  };
}

function reduceTestProgress(
  state: WorkbenchRunState,
  envelope: WorkbenchRunEnvelope,
  sequence: number,
): WorkbenchRunState {
  const repositoryKey = optionalIdentifier(envelope.data.repositoryKey, 256);
  const suite = optionalIdentifier(envelope.data.suite, 512);
  const status = optionalIdentifier(envelope.data.status, 80);
  const summary = requiredText(envelope.data.summary, 4000);
  if (!repositoryKey || !suite || !status || summary == null) return state;
  const progress: WorkbenchTestProgress = {
    repositoryKey,
    suite,
    status,
    summary,
    eventId: sequence,
    occurredAt: envelope.occurredAt,
  };
  return {
    ...state,
    lastAppliedEventSeq: sequence,
    testProgress: upsertBounded(
      state.testProgress,
      progress,
      item => `${item.repositoryKey}\n${item.suite}`,
      WORKBENCH_RUN_LIMITS.testProgress,
    ),
  };
}

function reduceOperationProposed(
  state: WorkbenchRunState,
  envelope: WorkbenchRunEnvelope,
  sequence: number,
): WorkbenchRunState {
  const operationId = optionalIdentifier(envelope.data.operationId, 128);
  const type = optionalIdentifier(envelope.data.type, 80);
  const summary = requiredText(envelope.data.summary, 4000);
  const target = boundedJsonClone(
    envelope.data.target,
    WORKBENCH_RUN_LIMITS.operationTargetChars,
  );
  if (!operationId || !type || summary == null || target === undefined) return state;
  const operation: WorkbenchOperationProposal = {
    operationId,
    type,
    target,
    summary,
    eventId: sequence,
    occurredAt: envelope.occurredAt,
  };
  return {
    ...state,
    lastAppliedEventSeq: sequence,
    operations: upsertBounded(
      state.operations,
      operation,
      item => item.operationId,
      WORKBENCH_RUN_LIMITS.operations,
    ),
  };
}

function reduceTerminal(
  state: WorkbenchRunState,
  envelope: WorkbenchRunEnvelope,
  sequence: number,
): WorkbenchRunState {
  const status = knownValue(envelope.data.status, TERMINAL_STATUSES);
  if (!status) return state;
  const failureCode = optionalText(envelope.data.failureCode, 256);
  const publicMessage = optionalText(envelope.data.publicMessage, 4000);
  if (failureCode === undefined || publicMessage === undefined) return state;
  const terminal: WorkbenchRunTerminal = {
    status,
    failureCode,
    publicMessage,
    eventId: sequence,
    occurredAt: envelope.occurredAt,
  };
  return {
    ...state,
    status,
    terminal,
    lastAppliedEventSeq: sequence,
  };
}

function reduceGeneric(
  state: WorkbenchRunState,
  envelope: WorkbenchRunEnvelope,
  sequence: number,
  eventType: string,
): WorkbenchRunState {
  return withBlock(state, sequence, {
    kind: 'generic',
    eventId: sequence,
    occurredAt: envelope.occurredAt,
    eventType,
    summary: boundedJsonSummary(
      envelope.data,
      WORKBENCH_RUN_LIMITS.genericSummaryChars,
    ),
  });
}

function withBlock(
  state: WorkbenchRunState,
  sequence: number,
  block: WorkbenchRunBlock,
): WorkbenchRunState {
  return {
    ...state,
    lastAppliedEventSeq: sequence,
    blocks: appendBounded(
      state.blocks,
      block,
      WORKBENCH_RUN_LIMITS.blocks,
    ),
  };
}

function parseEnvelope(
  raw: unknown,
  context: WorkbenchRunContext,
): WorkbenchRunEnvelope | null {
  let parsed: unknown;
  try {
    if (typeof raw === 'string') {
      if (raw.length > WORKBENCH_RUN_LIMITS.eventPayloadChars) return null;
      parsed = JSON.parse(raw) as unknown;
    } else {
      const serialized = JSON.stringify(raw);
      if (!serialized || serialized.length > WORKBENCH_RUN_LIMITS.eventPayloadChars) {
        return null;
      }
      parsed = JSON.parse(serialized) as unknown;
    }
  } catch {
    return null;
  }
  if (!isRecord(parsed)
      || parsed.schemaVersion !== EVENT_SCHEMA_VERSION
      || parsed.workbenchId !== context.workbenchId
      || parsed.phase !== context.phase
      || parsed.runId !== context.runId
      || !isRecord(parsed.data)) {
    return null;
  }
  const occurredAt = finiteNonNegativeInteger(parsed.occurredAt);
  return occurredAt == null ? null : { occurredAt, data: parsed.data };
}

function eventSequence(value: unknown): number | null {
  const text = typeof value === 'number' ? String(value) : value;
  if (typeof text !== 'string' || !/^[1-9]\d*$/.test(text)) return null;
  const sequence = Number(text);
  return Number.isSafeInteger(sequence) ? sequence : null;
}

function safeEventType(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.trim();
  return normalized
    && normalized.length <= 128
    && !containsControlCharacter(normalized)
    ? normalized
    : null;
}

function sameContext(left: WorkbenchRunContext, right: WorkbenchRunContext): boolean {
  return left.workbenchId === right.workbenchId
    && left.phase === right.phase
    && left.runId === right.runId;
}

function knownValue<T extends string>(value: unknown, known: Set<T>): T | null {
  return typeof value === 'string' && known.has(value as T) ? value as T : null;
}

function optionalIdentifier(
  value: unknown,
  maximum: number,
  allowMissing = false,
): string | null {
  if (value == null && allowMissing) return null;
  if (typeof value !== 'string') return null;
  const normalized = value.trim();
  return normalized
    && normalized.length <= maximum
    && !containsControlCharacter(normalized)
    ? normalized
    : null;
}

function relativePath(value: unknown): string | null {
  if (typeof value !== 'string'
      || !value
      || value.length > 4096
      || value.startsWith('/')
      || value.indexOf('\\') >= 0
      || /^[A-Za-z]:/.test(value)
      || containsControlCharacter(value)) {
    return null;
  }
  const segments = value.split('/');
  return segments.some(segment => !segment || segment === '.' || segment === '..')
    ? null
    : value;
}

function safeInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) ? value : null;
}

function appendBounded<T>(
  values: ReadonlyArray<T>,
  value: T,
  maximum: number,
): ReadonlyArray<T> {
  const appended = values.concat(value);
  return appended.length <= maximum
    ? appended
    : appended.slice(appended.length - maximum);
}

function upsertBounded<T>(
  values: ReadonlyArray<T>,
  value: T,
  identity: (item: T) => string,
  maximum: number,
): ReadonlyArray<T> {
  const key = identity(value);
  const withoutPrevious = values.filter(item => identity(item) !== key);
  return appendBounded(withoutPrevious, value, maximum);
}

function boundedJsonClone(value: unknown, maximum: number): unknown | undefined {
  try {
    const serialized = JSON.stringify(value);
    if (serialized === undefined || serialized.length > maximum) return undefined;
    return JSON.parse(serialized) as unknown;
  } catch {
    return undefined;
  }
}

function boundedJsonSummary(value: unknown, maximum: number): string {
  try {
    return truncate(JSON.stringify(value) || '{}', maximum);
  } catch {
    return '{}';
  }
}

function truncate(value: string, maximum: number): string {
  return value.length <= maximum ? value : value.substring(0, maximum);
}

function containsControlCharacter(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    if (value.charCodeAt(index) < 32 || value.charCodeAt(index) === 127) return true;
  }
  return false;
}

function containsDisallowedControlCharacter(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    if ((code < 32 && code !== 9 && code !== 10 && code !== 13) || code === 127) {
      return true;
    }
  }
  return false;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function requireCursor(value: number): number {
  const cursor = finiteNonNegativeInteger(value);
  if (cursor == null) throw new IllegalStateError('event cursor is invalid');
  return cursor;
}

/**
 * 不向调用方暴露浏览器原生错误类型差异。
 */
class IllegalStateError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'IllegalStateError';
  }
}
