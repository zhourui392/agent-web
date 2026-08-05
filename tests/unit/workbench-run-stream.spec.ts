/**
 * Workbench Run resumable stream orchestration contract.
 *
 * @author alex
 * @since 2026-08-01
 */
// Vitest 工程与 frontend 各自安装依赖；生命周期测试必须复用 composable 实际加载的 Vue 实例。
// @ts-expect-error Vue 的直接 ESM 入口没有为相对路径暴露声明文件。
import * as frontendVueRuntime from '../../frontend/node_modules/vue/index.mjs';
import { describe, expect, it, vi } from 'vitest';
import { useWorkbenchRunStream } from '../../frontend/js/composables/useWorkbenchRunStream.js';
import {
  createWorkbenchRunMarkerStore,
  type StorageLike,
  type WorkbenchRunMarkerIdentity,
} from '../../frontend/js/lib/workbench-run-state.js';
import type {
  WorkbenchRunStreamClient,
  WorkbenchRunStreamEvent,
  WorkbenchRunStreamTransport,
  WorkbenchRunStreamTransportOptions,
} from '../../frontend/js/composables/useWorkbenchRunStream.js';

const { effectScope, nextTick, ref } = frontendVueRuntime as typeof import('vue');

const IDENTITY: WorkbenchRunMarkerIdentity = {
  userId: 'user/a',
  workbenchId: 'workbench/1',
  stageInstanceIdentifier: 'stage-delivery',
  conversationGeneration: 2,
};

function memoryStorage(): StorageLike & { values: Record<string, string> } {
  const values: Record<string, string> = {};
  return {
    values,
    getItem: key => values[key] ?? null,
    setItem: (key, value) => { values[key] = value; },
    removeItem: key => { delete values[key]; },
  };
}

class FakeClient implements WorkbenchRunStreamClient {
  readonly close = vi.fn();
  private readonly listeners: Record<string, Array<(event: WorkbenchRunStreamEvent) => void>> = {};

  addEventListener(type: string, listener: (event: WorkbenchRunStreamEvent) => void): void {
    (this.listeners[type] || (this.listeners[type] = [])).push(listener);
  }

  emit(type: string, data = '', lastEventId = ''): void {
    const event = { type, data, lastEventId };
    for (const listener of this.listeners[type] || []) listener(event);
    for (const listener of this.listeners['*'] || []) listener(event);
  }
}

function fakeTransport(): {
  transport: WorkbenchRunStreamTransport;
  open: ReturnType<typeof vi.fn>;
  clients: FakeClient[];
} {
  const clients: FakeClient[] = [];
  const open = vi.fn((_url: string, _options: WorkbenchRunStreamTransportOptions) => {
    const client = new FakeClient();
    clients.push(client);
    return client;
  });
  return { transport: { open }, open, clients };
}

function envelope(
  runId: string,
  data: Record<string, unknown>,
  overrides: Partial<{ workbenchId: string; stageInstanceIdentifier: string; occurredAt: number }> = {},
): string {
  return JSON.stringify({
    schemaVersion: 'workbench-run-event@1',
    runId,
    workbenchId: overrides.workbenchId ?? IDENTITY.workbenchId,
    stageInstanceIdentifier: overrides.stageInstanceIdentifier ?? IDENTITY.stageInstanceIdentifier,
    occurredAt: overrides.occurredAt ?? 100,
    data,
  });
}

describe('useWorkbenchRunStream', () => {
  it('resumes the persisted run with its cursor and bounded reconnect settings', () => {
    const storage = memoryStorage();
    createWorkbenchRunMarkerStore(storage, IDENTITY).save('run/1', 17);
    const { transport, open } = fakeTransport();
    const stream = useWorkbenchRunStream({
      identity: ref(IDENTITY),
      storage,
      transport,
      retryBaseMs: 500,
      retryMaxMs: 8_000,
    });

    expect(stream.resume()).toBe(true);
    expect(open).toHaveBeenCalledWith(
      '/api/workbenches/workbench%2F1/runs/run%2F1/events',
      { after: 17, retryBaseMs: 500, retryMaxMs: 8_000 },
    );
    expect(stream.state.value?.context.runId).toBe('run/1');
    expect(stream.state.value?.lastAppliedEventSeq).toBe(17);
  });

  it('refuses to resume a persisted marker for a different active run', () => {
    const storage = memoryStorage();
    createWorkbenchRunMarkerStore(storage, IDENTITY).save('run-old', 17);
    const { transport, open } = fakeTransport();
    const stream = useWorkbenchRunStream({ identity: ref(IDENTITY), storage, transport });

    expect(stream.resume('run-active')).toBe(false);
    expect(open).not.toHaveBeenCalled();
    expect(stream.state.value).toBeNull();
  });

  it('passes raw SSE id, type, and data to the reducer and persists only accepted cursors', () => {
    const storage = memoryStorage();
    const { transport, clients } = fakeTransport();
    const stream = useWorkbenchRunStream({ identity: ref(IDENTITY), storage, transport });
    stream.attach('run-1', 4);

    clients[0].emit('run_status', envelope('run-1', {
      status: 'RUNNING',
      runMode: 'MODIFY_WORKSPACE',
    }), '5');
    expect(stream.state.value).toEqual(expect.objectContaining({
      status: 'RUNNING',
      runMode: 'MODIFY_WORKSPACE',
      lastAppliedEventSeq: 5,
    }));
    expect(createWorkbenchRunMarkerStore(storage, IDENTITY).load()?.lastAppliedEventSeq).toBe(5);

    clients[0].emit('agent_chunk', envelope('run-1', { content: 'duplicate' }), '5');
    clients[0].emit('agent_chunk', '{broken-json', '6');
    expect(stream.state.value?.blocks).toHaveLength(0);
    expect(createWorkbenchRunMarkerStore(storage, IDENTITY).load()?.lastAppliedEventSeq).toBe(5);
  });

  it('closes at terminal, clears the marker, and ignores late events', () => {
    const storage = memoryStorage();
    const { transport, clients } = fakeTransport();
    const stream = useWorkbenchRunStream({ identity: ref(IDENTITY), storage, transport });
    expect(stream.attach('run-1')).toBe(true);

    clients[0].emit('terminal', envelope('run-1', {
      status: 'SUCCEEDED',
      failureCode: null,
      publicMessage: 'completed',
    }), '1');

    expect(stream.state.value?.terminal?.status).toBe('SUCCEEDED');
    expect(clients[0].close).toHaveBeenCalledTimes(1);
    expect(createWorkbenchRunMarkerStore(storage, IDENTITY).load()).toBeNull();

    clients[0].emit('agent_chunk', envelope('run-1', { content: 'late' }), '2');
    expect(stream.state.value?.lastAppliedEventSeq).toBe(1);
    expect(stream.state.value?.blocks).toHaveLength(0);
  });

  it.each(['unauthorized', 'fatal'] as const)(
    'does not reopen after an authentication failure delivered as %s',
    eventType => {
      const storage = memoryStorage();
      const { transport, open, clients } = fakeTransport();
      const stream = useWorkbenchRunStream({ identity: ref(IDENTITY), storage, transport });
      stream.attach('run-1', 3);

      clients[0].emit(eventType, eventType === 'fatal' ? 'HTTP 403' : '');

      expect(clients[0].close).toHaveBeenCalledTimes(1);
      expect(open).toHaveBeenCalledTimes(1);
      expect(stream.error.value).toEqual({ code: 'UNAUTHORIZED', recoverable: false });
      expect(createWorkbenchRunMarkerStore(storage, IDENTITY).load()?.lastAppliedEventSeq).toBe(3);
    },
  );

  it('clears an expired cursor and exposes a recoverable error without retaining response details', () => {
    const storage = memoryStorage();
    const { transport, clients } = fakeTransport();
    const stream = useWorkbenchRunStream({ identity: ref(IDENTITY), storage, transport });
    stream.attach('run-1', 9);

    clients[0].emit(
      'cursor_expired',
      '{"workingDir":"/home/private/project","token":"secret-value"}',
    );

    expect(stream.error.value).toEqual({ code: 'CURSOR_EXPIRED', recoverable: true });
    expect(JSON.stringify(stream.error.value)).not.toMatch(/home|workingDir|secret|token/i);
    expect(createWorkbenchRunMarkerStore(storage, IDENTITY).load()).toBeNull();
    expect(clients[0].close).toHaveBeenCalledTimes(1);
  });

  it('reports reconnecting without copying transport diagnostics into state', () => {
    const storage = memoryStorage();
    const { transport, clients } = fakeTransport();
    const stream = useWorkbenchRunStream({ identity: ref(IDENTITY), storage, transport });
    expect(stream.attach('run-1')).toBe(true);

    clients[0].emit(
      'reconnecting',
      '{"reason":"failed at /home/private/project with secret-value","delayMs":1000}',
    );

    expect(stream.connectionStatus.value).toBe('reconnecting');
    expect(JSON.stringify({ error: stream.error.value, status: stream.connectionStatus.value }))
      .not.toMatch(/home|secret|reason/i);
  });

  it('closes and invalidates the old stream when any identity dimension changes', async () => {
    const identity = ref<WorkbenchRunMarkerIdentity | null>(IDENTITY);
    const storage = memoryStorage();
    const { transport, clients } = fakeTransport();
    const scope = effectScope();
    const stream = scope.run(() => useWorkbenchRunStream({ identity, storage, transport }))!;
    expect(stream.attach('run-1')).toBe(true);

    identity.value = { ...IDENTITY, conversationGeneration: 3 };
    await nextTick();

    expect(clients[0].close).toHaveBeenCalledTimes(1);
    expect(stream.state.value).toBeNull();
    clients[0].emit('agent_chunk', envelope('run-1', { content: 'stale' }), '1');
    expect(stream.state.value).toBeNull();
    scope.stop();
  });

  it('closes the active stream when its Vue scope is disposed', () => {
    const storage = memoryStorage();
    const { transport, clients } = fakeTransport();
    const scope = effectScope();
    const stream = scope.run(() => useWorkbenchRunStream({
      identity: ref(IDENTITY),
      storage,
      transport,
    }))!;
    expect(stream.attach('run-1')).toBe(true);

    scope.stop();

    expect(clients[0].close).toHaveBeenCalledTimes(1);
  });
});
