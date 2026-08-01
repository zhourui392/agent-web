/**
 * Workbench Run 可恢复 SSE 的 Vue 生命周期编排。
 *
 * 仅持有结构化身份、Run ID、游标和安全投影；传输协议复用公共 resumable SSE client。
 *
 * @author alex
 * @since 2026-08-01
 */
import {
  getCurrentScope,
  onScopeDispose,
  shallowRef,
  watch,
  type Ref,
  type ShallowRef,
} from 'vue';
import { open as openResumableSse } from '../lib/resumable-sse-client.js';
import {
  applyWorkbenchRunEvent,
  createWorkbenchRunMarkerStore,
  createWorkbenchRunState,
  type StorageLike,
  type WorkbenchRunContext,
  type WorkbenchRunMarkerIdentity,
  type WorkbenchRunMarkerStore,
  type WorkbenchRunState,
} from '../lib/workbench-run-state.js';

const DEFAULT_RETRY_BASE_MS = 1_000;
const DEFAULT_RETRY_MAX_MS = 15_000;

export type WorkbenchRunConnectionStatus =
  | 'idle'
  | 'connecting'
  | 'streaming'
  | 'reconnecting'
  | 'closed';

export type WorkbenchRunStreamErrorCode =
  | 'UNAUTHORIZED'
  | 'CURSOR_EXPIRED'
  | 'STREAM_FAILED';

export interface WorkbenchRunStreamError {
  code: WorkbenchRunStreamErrorCode;
  recoverable: boolean;
}

export interface WorkbenchRunStreamEvent {
  type: string;
  data: string;
  lastEventId: string;
}

export interface WorkbenchRunStreamClient {
  addEventListener(
    type: string,
    listener: (event: WorkbenchRunStreamEvent) => void,
  ): void;
  close(): void;
}

export interface WorkbenchRunStreamTransportOptions {
  after: number;
  retryBaseMs: number;
  retryMaxMs: number;
}

export interface WorkbenchRunStreamTransport {
  open(
    url: string,
    options: WorkbenchRunStreamTransportOptions,
  ): WorkbenchRunStreamClient;
}

export interface UseWorkbenchRunStreamOptions {
  identity: Ref<WorkbenchRunMarkerIdentity | null>;
  storage?: StorageLike;
  transport?: WorkbenchRunStreamTransport;
  eventUrl?: (workbenchId: string, runId: string) => string;
  retryBaseMs?: number;
  retryMaxMs?: number;
}

export interface UseWorkbenchRunStream {
  state: ShallowRef<WorkbenchRunState | null>;
  error: ShallowRef<WorkbenchRunStreamError | null>;
  connectionStatus: ShallowRef<WorkbenchRunConnectionStatus>;
  attach(runId: string, cursor?: number): boolean;
  resume(expectedRunId?: string): boolean;
  close(): void;
}

const DEFAULT_TRANSPORT: WorkbenchRunStreamTransport = {
  open(url, options): WorkbenchRunStreamClient {
    return openResumableSse(url, options);
  },
};

function defaultEventUrl(workbenchId: string, runId: string): string {
  return '/api/workbenches/' + encodeURIComponent(workbenchId)
    + '/runs/' + encodeURIComponent(runId) + '/events';
}

function identityFingerprint(identity: WorkbenchRunMarkerIdentity | null): string {
  if (!identity) return '';
  return JSON.stringify([
    identity.userId,
    identity.workbenchId,
    identity.phase,
    identity.conversationGeneration,
  ]);
}

function initialState(context: WorkbenchRunContext, cursor: number): WorkbenchRunState {
  const created = createWorkbenchRunState(context);
  return cursor === 0 ? created : { ...created, lastAppliedEventSeq: cursor };
}

function positiveDuration(value: number | undefined, fallback: number): number {
  const candidate = Number(value);
  return Number.isFinite(candidate) && candidate > 0 ? candidate : fallback;
}

function eventCursor(value: number): number {
  const candidate = Number(value);
  return Number.isSafeInteger(candidate) && candidate >= 0 ? candidate : 0;
}

export function useWorkbenchRunStream(
  options: UseWorkbenchRunStreamOptions,
): UseWorkbenchRunStream {
  const state = shallowRef<WorkbenchRunState | null>(null);
  const error = shallowRef<WorkbenchRunStreamError | null>(null);
  const connectionStatus = shallowRef<WorkbenchRunConnectionStatus>('idle');
  const transport = options.transport || DEFAULT_TRANSPORT;
  const buildEventUrl = options.eventUrl || defaultEventUrl;
  const retryBaseMs = positiveDuration(options.retryBaseMs, DEFAULT_RETRY_BASE_MS);
  const retryMaxMs = Math.max(
    retryBaseMs,
    positiveDuration(options.retryMaxMs, DEFAULT_RETRY_MAX_MS),
  );
  let activeClient: WorkbenchRunStreamClient | null = null;
  let connectionGeneration = 0;

  function storage(): StorageLike {
    return options.storage || globalThis.localStorage;
  }

  function markerStore(): WorkbenchRunMarkerStore | null {
    const identity = options.identity.value;
    if (!identity) return null;
    try {
      return createWorkbenchRunMarkerStore(storage(), identity);
    } catch {
      return null;
    }
  }

  function closeActive(nextStatus: WorkbenchRunConnectionStatus): void {
    connectionGeneration++;
    const client = activeClient;
    activeClient = null;
    if (client) client.close();
    connectionStatus.value = nextStatus;
  }

  function safelySaveMarker(
    store: WorkbenchRunMarkerStore,
    runId: string,
    cursor: number,
  ): void {
    try {
      store.save(runId, cursor);
    } catch {
      // 浏览器存储不可用时，流仍可继续；刷新恢复能力按 fail-safe 降级。
    }
  }

  function safelyClearMarker(store: WorkbenchRunMarkerStore): void {
    try {
      store.clear();
    } catch {
      // 清理失败不应阻断终态或错误收口。
    }
  }

  function fail(
    failure: WorkbenchRunStreamError,
    store: WorkbenchRunMarkerStore,
    clearMarker: boolean,
  ): void {
    if (clearMarker) safelyClearMarker(store);
    error.value = failure;
    closeActive('closed');
  }

  function handleTransportEvent(
    event: WorkbenchRunStreamEvent,
    context: WorkbenchRunContext,
    store: WorkbenchRunMarkerStore,
    client: WorkbenchRunStreamClient,
    generation: number,
  ): void {
    if (generation !== connectionGeneration || client !== activeClient) return;

    if (event.type === 'reconnecting') {
      connectionStatus.value = 'reconnecting';
      return;
    }
    if (event.type === 'unauthorized') {
      fail({ code: 'UNAUTHORIZED', recoverable: false }, store, false);
      return;
    }
    if (event.type === 'cursor_expired') {
      fail({ code: 'CURSOR_EXPIRED', recoverable: true }, store, true);
      return;
    }
    if (event.type === 'fatal') {
      const authenticationFailure = event.data === 'HTTP 401' || event.data === 'HTTP 403';
      fail(authenticationFailure
        ? { code: 'UNAUTHORIZED', recoverable: false }
        : { code: 'STREAM_FAILED', recoverable: false }, store, false);
      return;
    }

    connectionStatus.value = 'streaming';
    const current = state.value;
    if (!current) return;
    const reduced = applyWorkbenchRunEvent(current, {
      id: event.lastEventId,
      type: event.type,
      data: event.data,
    }, context);
    if (reduced === current) return;

    state.value = reduced;
    safelySaveMarker(store, context.runId, reduced.lastAppliedEventSeq);
    if (reduced.terminal) {
      safelyClearMarker(store);
      closeActive('closed');
    }
  }

  function attach(runId: string, cursor = 0): boolean {
    const identity = options.identity.value;
    const store = markerStore();
    if (!identity || !store) return false;

    closeActive('closed');
    error.value = null;
    const normalizedCursor = eventCursor(cursor);
    const context: WorkbenchRunContext = {
      workbenchId: identity.workbenchId,
      phase: identity.phase,
      runId,
    };
    try {
      state.value = initialState(context, normalizedCursor);
      safelySaveMarker(store, runId, normalizedCursor);
      connectionStatus.value = 'connecting';
      const client = transport.open(buildEventUrl(context.workbenchId, context.runId), {
        after: normalizedCursor,
        retryBaseMs,
        retryMaxMs,
      });
      activeClient = client;
      const generation = connectionGeneration;
      client.addEventListener('*', event => {
        handleTransportEvent(event, context, store, client, generation);
      });
      return true;
    } catch {
      error.value = { code: 'STREAM_FAILED', recoverable: false };
      closeActive('closed');
      return false;
    }
  }

  function resume(expectedRunId?: string): boolean {
    const store = markerStore();
    if (!store) return false;
    const marker = store.load();
    if (!marker || expectedRunId != null && marker.runId !== expectedRunId) {
      return false;
    }
    return attach(marker.runId, marker.lastAppliedEventSeq);
  }

  function close(): void {
    closeActive('closed');
  }

  watch(
    () => identityFingerprint(options.identity.value),
    () => {
      closeActive('closed');
      state.value = null;
      error.value = null;
    },
    { flush: 'sync' },
  );

  if (getCurrentScope()) onScopeDispose(close);

  return { state, error, connectionStatus, attach, resume, close };
}
