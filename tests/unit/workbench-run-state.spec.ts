/**
 * Workbench Run marker isolation and resumable SSE reducer contract.
 *
 * @author alex
 * @since 2026-08-01
 */
import { describe, expect, it } from 'vitest';
import {
  WORKBENCH_RUN_LIMITS,
  applyWorkbenchRunEvent,
  createWorkbenchRunMarkerStore,
  createWorkbenchRunState,
  workbenchRunMarkerStorageKey,
  type WorkbenchRunContext,
  type WorkbenchRunMarkerIdentity,
  type WorkbenchRunState,
} from '../../frontend/js/lib/workbench-run-state.js';

type MemoryStorage = {
  values: Record<string, string>;
  getItem: (key: string) => string | null;
  setItem: (key: string, value: string) => void;
  removeItem: (key: string) => void;
};

const MARKER_IDENTITY: WorkbenchRunMarkerIdentity = {
  userId: 'user/a',
  workbenchId: 'workbench:1',
  phase: 'IMPLEMENT_TEST',
  conversationGeneration: 2,
};

const RUN_CONTEXT: WorkbenchRunContext = {
  workbenchId: MARKER_IDENTITY.workbenchId,
  phase: MARKER_IDENTITY.phase,
  runId: 'run-1',
};

function memoryStorage(): MemoryStorage {
  const values: Record<string, string> = {};
  return {
    values,
    getItem: key => values[key] ?? null,
    setItem: (key, value) => { values[key] = value; },
    removeItem: key => { delete values[key]; },
  };
}

function event(
  id: string | number,
  type: string,
  data: Record<string, unknown>,
  overrides: Partial<{
    schemaVersion: string;
    runId: string;
    workbenchId: string;
    phase: string;
    occurredAt: number;
  }> = {},
) {
  return {
    id: String(id),
    type,
    data: JSON.stringify({
      schemaVersion: overrides.schemaVersion ?? 'workbench-run-event@1',
      runId: overrides.runId ?? RUN_CONTEXT.runId,
      workbenchId: overrides.workbenchId ?? RUN_CONTEXT.workbenchId,
      phase: overrides.phase ?? RUN_CONTEXT.phase,
      occurredAt: overrides.occurredAt ?? Number(id) * 100,
      data,
    }),
  };
}

function reduce(
  state: WorkbenchRunState,
  id: string | number,
  type: string,
  data: Record<string, unknown>,
): WorkbenchRunState {
  return applyWorkbenchRunEvent(state, event(id, type, data), RUN_CONTEXT);
}

describe('workbench run browser marker', () => {
  it('isolates markers by authenticated user, workbench, phase, and conversation generation', () => {
    const base = workbenchRunMarkerStorageKey(MARKER_IDENTITY);

    expect(base).not.toBe(workbenchRunMarkerStorageKey({
      ...MARKER_IDENTITY,
      userId: 'user/b',
    }));
    expect(base).not.toBe(workbenchRunMarkerStorageKey({
      ...MARKER_IDENTITY,
      workbenchId: 'workbench:2',
    }));
    expect(base).not.toBe(workbenchRunMarkerStorageKey({
      ...MARKER_IDENTITY,
      phase: 'REVIEW_REFACTOR',
    }));
    expect(base).not.toBe(workbenchRunMarkerStorageKey({
      ...MARKER_IDENTITY,
      conversationGeneration: 3,
    }));
    expect(() => workbenchRunMarkerStorageKey({
      ...MARKER_IDENTITY,
      userId: '',
    })).toThrow(/authenticated user/i);
  });

  it('stores only run identity and numeric cursor without absolute working directories', () => {
    const storage = memoryStorage();
    const store = createWorkbenchRunMarkerStore(storage, MARKER_IDENTITY);

    store.save('run-1', 17);

    expect(store.load()).toEqual({
      schemaVersion: 'workbench-run-marker@1',
      ...MARKER_IDENTITY,
      runId: 'run-1',
      lastAppliedEventSeq: 17,
    });
    const raw = storage.values[workbenchRunMarkerStorageKey(MARKER_IDENTITY)];
    expect(raw).not.toContain('workingDir');
    expect(raw).not.toContain('/home/');

    store.clear();
    expect(store.load()).toBeNull();
  });

  it('fails safe and discards malformed, mismatched, or unsafe persisted markers', () => {
    const storage = memoryStorage();
    const key = workbenchRunMarkerStorageKey(MARKER_IDENTITY);
    const store = createWorkbenchRunMarkerStore(storage, MARKER_IDENTITY);

    storage.values[key] = '{broken-json';
    expect(store.load()).toBeNull();
    expect(storage.values[key]).toBeUndefined();

    storage.values[key] = JSON.stringify({
      schemaVersion: 'workbench-run-marker@1',
      ...MARKER_IDENTITY,
      userId: 'another-user',
      runId: 'run-foreign',
      lastAppliedEventSeq: 1,
    });
    expect(store.load()).toBeNull();

    storage.values[key] = JSON.stringify({
      schemaVersion: 'workbench-run-marker@1',
      ...MARKER_IDENTITY,
      runId: 'run-1',
      lastAppliedEventSeq: -1,
      workingDir: '/absolute/path/must/not/leak',
    });
    expect(store.load()).toBeNull();
  });
});

describe('workbench run SSE reducer', () => {
  it('applies strictly increasing numeric Event IDs and rejects duplicate, reversed, or malformed IDs', () => {
    const initial = createWorkbenchRunState(RUN_CONTEXT);
    const running = reduce(initial, 2, 'run_status', {
      status: 'RUNNING',
      runMode: 'MODIFY_WORKSPACE',
    });

    expect(running.lastAppliedEventSeq).toBe(2);
    expect(running.status).toBe('RUNNING');
    expect(applyWorkbenchRunEvent(running, event(2, 'agent_chunk', {
      content: 'duplicate',
    }), RUN_CONTEXT)).toBe(running);
    expect(applyWorkbenchRunEvent(running, event(1, 'agent_chunk', {
      content: 'reversed',
    }), RUN_CONTEXT)).toBe(running);
    expect(applyWorkbenchRunEvent(running, {
      id: 'not-numeric',
      type: 'agent_chunk',
      data: event(3, 'agent_chunk', { content: 'invalid' }).data,
    }, RUN_CONTEXT)).toBe(running);
  });

  it('fails closed when schema or Workbench Run identity does not match the active context', () => {
    const initial = createWorkbenchRunState(RUN_CONTEXT);
    const candidates = [
      event(1, 'run_status', { status: 'RUNNING' }, { schemaVersion: 'unknown@9' }),
      event(1, 'run_status', { status: 'RUNNING' }, { workbenchId: 'foreign' }),
      event(1, 'run_status', { status: 'RUNNING' }, { phase: 'SOLUTION_DESIGN' }),
      event(1, 'run_status', { status: 'RUNNING' }, { runId: 'another-run' }),
      { id: '1', type: 'run_status', data: '{broken-json' },
    ];

    for (const candidate of candidates) {
      expect(applyWorkbenchRunEvent(initial, candidate, RUN_CONTEXT)).toBe(initial);
    }
    expect(initial.lastAppliedEventSeq).toBe(0);
  });

  it('reduces lifecycle, text, tool, command, file, test, operation, and terminal events', () => {
    let state = createWorkbenchRunState(RUN_CONTEXT);
    state = reduce(state, 1, 'run_status', {
      status: 'RUNNING',
      runMode: 'MODIFY_WORKSPACE',
    });
    state = reduce(state, 2, 'agent_chunk', { content: 'implementing' });
    state = reduce(state, 3, 'tool_started', {
      tool: 'shell',
      callId: 'tool-1',
      status: 'RUNNING',
    });
    state = reduce(state, 4, 'tool_finished', {
      tool: 'shell',
      callId: 'tool-1',
      status: 'SUCCEEDED',
      durationMs: 25,
    });
    state = reduce(state, 5, 'command_started', {
      repositoryKey: 'agent-web',
      commandClass: 'TEST',
      status: 'RUNNING',
      commandSummary: '在仓库 agent-web 执行 TEST 类命令',
      command: './mvnw -q test',
    });
    state = reduce(state, 6, 'command_finished', {
      repositoryKey: 'agent-web',
      commandClass: 'TEST',
      exitCode: 0,
      status: 'SUCCEEDED',
      commandSummary: '在仓库 agent-web 执行 TEST 类命令',
      outputSummary: 'TEST 类命令执行成功（退出码 0）',
      aggregated_output: '/home/alex/secret decoder-secret-never-visible',
    });
    state = reduce(state, 7, 'file_changed', {
      repositoryKey: 'agent-web',
      path: 'src/App.java',
      changeType: 'MODIFIED',
      contentVersion: 'sha256:v2',
    });
    state = reduce(state, 8, 'test_progress', {
      repositoryKey: 'agent-web',
      suite: 'WorkbenchRunStateTest',
      status: 'PASSED',
      summary: '12 tests passed',
    });
    state = reduce(state, 9, 'operation_proposed', {
      operationId: 'operation-1',
      type: 'GIT_PUSH',
      target: { repositoryKey: 'agent-web', branch: 'master' },
      summary: 'Push reviewed commit',
    });
    state = reduce(state, 10, 'terminal', {
      status: 'SUCCEEDED',
      failureCode: null,
      publicMessage: 'completed',
    });

    expect(state.status).toBe('SUCCEEDED');
    expect(state.runMode).toBe('MODIFY_WORKSPACE');
    expect(state.lastAppliedEventSeq).toBe(10);
    expect(state.blocks.map(block => block.kind)).toEqual([
      'agent_chunk',
      'tool_started',
      'tool_finished',
      'command_started',
      'command_finished',
    ]);
    expect(state.blocks[3]).toEqual(expect.objectContaining({
      status: 'RUNNING',
      commandSummary: '在仓库 agent-web 执行 TEST 类命令',
    }));
    expect(state.blocks[4]).toEqual(expect.objectContaining({
      status: 'SUCCEEDED',
      commandSummary: '在仓库 agent-web 执行 TEST 类命令',
      outputSummary: 'TEST 类命令执行成功（退出码 0）',
    }));
    expect(JSON.stringify(state.blocks)).not.toContain('./mvnw');
    expect(JSON.stringify(state.blocks)).not.toContain('/home/alex/secret');
    expect(JSON.stringify(state.blocks)).not.toContain('decoder-secret-never-visible');
    expect(state.staleDocuments).toEqual([expect.objectContaining({
      repositoryKey: 'agent-web',
      path: 'src/App.java',
      contentVersion: 'sha256:v2',
      stale: true,
    })]);
    expect(state.testProgress).toEqual([expect.objectContaining({
      suite: 'WorkbenchRunStateTest',
      status: 'PASSED',
    })]);
    expect(state.operations).toEqual([expect.objectContaining({
      operationId: 'operation-1',
      type: 'GIT_PUSH',
    })]);
    expect(state.terminal).toEqual(expect.objectContaining({
      status: 'SUCCEEDED',
      eventId: 10,
    }));
  });

  it('retains unknown events as bounded generic blocks without blocking later known events', () => {
    let state = createWorkbenchRunState(RUN_CONTEXT);
    state = reduce(state, 1, 'future_event', {
      detail: 'x'.repeat(WORKBENCH_RUN_LIMITS.genericSummaryChars + 100),
    });
    state = reduce(state, 2, 'agent_chunk', { content: 'still applied' });

    expect(state.lastAppliedEventSeq).toBe(2);
    expect(state.blocks[0]).toEqual(expect.objectContaining({
      kind: 'generic',
      eventType: 'future_event',
    }));
    expect(state.blocks[0].summary?.length)
      .toBeLessThanOrEqual(WORKBENCH_RUN_LIMITS.genericSummaryChars);
    expect(state.blocks[1]).toEqual(expect.objectContaining({
      kind: 'agent_chunk',
      content: 'still applied',
    }));
  });

  it('keeps the first terminal outcome immutable and ignores all later business events', () => {
    let state = createWorkbenchRunState(RUN_CONTEXT);
    state = reduce(state, 1, 'run_status', { status: 'RUNNING' });
    state = reduce(state, 2, 'terminal', {
      status: 'FAILED',
      failureCode: 'RUNTIME_FAILED',
      publicMessage: 'safe failure',
    });
    const terminalState = state;

    state = reduce(state, 3, 'agent_chunk', { content: 'must be ignored' });
    state = reduce(state, 4, 'terminal', { status: 'SUCCEEDED' });

    expect(state).toBe(terminalState);
    expect(state.status).toBe('FAILED');
    expect(state.terminal?.failureCode).toBe('RUNTIME_FAILED');
    expect(state.lastAppliedEventSeq).toBe(2);
    expect(state.blocks).toHaveLength(0);
  });

  it('bounds every growing collection while retaining the newest projections', () => {
    let state = createWorkbenchRunState(RUN_CONTEXT);
    let id = 1;
    for (let index = 0; index < WORKBENCH_RUN_LIMITS.blocks + 20; index++) {
      state = reduce(state, id++, 'agent_chunk', { content: `chunk-${index}` });
    }
    for (let index = 0; index < WORKBENCH_RUN_LIMITS.staleDocuments + 20; index++) {
      state = reduce(state, id++, 'file_changed', {
        repositoryKey: 'agent-web',
        path: `src/File${index}.java`,
        changeType: 'MODIFIED',
        contentVersion: `v${index}`,
      });
    }
    for (let index = 0; index < WORKBENCH_RUN_LIMITS.testProgress + 20; index++) {
      state = reduce(state, id++, 'test_progress', {
        repositoryKey: 'agent-web',
        suite: `suite-${index}`,
        status: 'PASSED',
        summary: 'ok',
      });
    }
    for (let index = 0; index < WORKBENCH_RUN_LIMITS.operations + 20; index++) {
      state = reduce(state, id++, 'operation_proposed', {
        operationId: `operation-${index}`,
        type: 'GIT_COMMIT',
        target: { repositoryKey: 'agent-web' },
        summary: 'commit',
      });
    }

    expect(state.blocks).toHaveLength(WORKBENCH_RUN_LIMITS.blocks);
    expect(state.blocks[0]).toEqual(expect.objectContaining({ content: 'chunk-20' }));
    expect(state.staleDocuments).toHaveLength(WORKBENCH_RUN_LIMITS.staleDocuments);
    expect(state.staleDocuments[state.staleDocuments.length - 1]?.path)
      .toBe(`src/File${WORKBENCH_RUN_LIMITS.staleDocuments + 19}.java`);
    expect(state.testProgress).toHaveLength(WORKBENCH_RUN_LIMITS.testProgress);
    expect(state.operations).toHaveLength(WORKBENCH_RUN_LIMITS.operations);
  });
});
