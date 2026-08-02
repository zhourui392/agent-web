/**
 * Workbench Run FILE_CHANGED 到 Document Pane 的作用域安全接线。
 *
 * @author alex
 * @since 2026-08-01
 */
import { readFile } from 'node:fs/promises';
// Vitest 工程与 frontend 各自安装依赖；生命周期测试必须复用 composable 实际加载的 Vue 实例。
// @ts-expect-error Vue 的直接 ESM 入口没有为相对路径暴露声明文件。
import * as frontendVueRuntime from '../../frontend/node_modules/vue/index.mjs';
import { describe, expect, it, vi } from 'vitest';
import {
  useWorkbenchDocumentRunIntegration,
  type WorkbenchRunStreamFactory,
} from '../../frontend/js/composables/useWorkbenchDocumentRunIntegration.js';
import type {
  WorkbenchDocumentEventScope,
  WorkbenchDocumentFileChangedEvent,
} from '../../frontend/js/composables/useWorkbenchDocumentPane.js';
import type {
  UseWorkbenchRunStream,
  UseWorkbenchRunStreamOptions,
} from '../../frontend/js/composables/useWorkbenchRunStream.js';
import {
  applyWorkbenchRunEvent,
  createWorkbenchRunState,
  type WorkbenchRunContext,
  type WorkbenchRunMarkerIdentity,
  type WorkbenchRunState,
} from '../../frontend/js/lib/workbench-run-state.js';
import type { WorkbenchPhase } from '../../frontend/js/lib/workbench-state.js';

const {
  effectScope,
  nextTick,
  ref,
  shallowRef,
} = frontendVueRuntime as typeof import('vue');

interface FakeStreamSetup {
  stream: UseWorkbenchRunStream;
  factory: WorkbenchRunStreamFactory;
  identity: { value: WorkbenchRunMarkerIdentity | null };
}

interface IntegrationSetup {
  vueScope: ReturnType<typeof effectScope>;
  userId: ReturnType<typeof ref<string>>;
  workbenchId: ReturnType<typeof ref<string | null>>;
  phase: ReturnType<typeof ref<WorkbenchPhase>>;
  conversationGeneration: ReturnType<typeof ref<number>>;
  activeRunId: ReturnType<typeof ref<string | null>>;
  archived: ReturnType<typeof ref<boolean>>;
  documentEventScope: ReturnType<typeof shallowRef<WorkbenchDocumentEventScope | null>>;
  receiveDocumentFileChanged: ReturnType<typeof vi.fn<(
    scope: WorkbenchDocumentEventScope | null,
    event: WorkbenchDocumentFileChangedEvent,
  ) => boolean>>;
  fake: FakeStreamSetup;
}

function documentScope(
  workbenchId = 'workbench-1',
  phase: WorkbenchPhase = 'IMPLEMENT_TEST',
  generation = 1,
): WorkbenchDocumentEventScope {
  return Object.freeze({
    userId: 'user-1',
    workbenchId,
    phase,
    generation,
  });
}

function fakeRunStream(): FakeStreamSetup {
  const state = shallowRef<WorkbenchRunState | null>(null);
  const error = shallowRef(null);
  const connectionStatus = shallowRef<'idle' | 'connecting' | 'streaming' | 'reconnecting' | 'closed'>('idle');
  let identity: UseWorkbenchRunStreamOptions['identity'] | null = null;
  const stream: UseWorkbenchRunStream = {
    state,
    error,
    connectionStatus,
    attach: vi.fn(() => true),
    resume: vi.fn(() => false),
    close: vi.fn(),
  };
  const factory: WorkbenchRunStreamFactory = vi.fn(options => {
    identity = options.identity;
    return stream;
  });
  return {
    stream,
    factory,
    get identity() {
      if (!identity) throw new Error('stream factory was not initialized');
      return identity;
    },
  };
}

function createSetup(): IntegrationSetup {
  const userId = ref('user-1');
  const workbenchId = ref<string | null>('workbench-1');
  const phase = ref<WorkbenchPhase>('IMPLEMENT_TEST');
  const conversationGeneration = ref(2);
  const activeRunId = ref<string | null>('run-active');
  const archived = ref(false);
  const documentEventScope = shallowRef<WorkbenchDocumentEventScope | null>(documentScope());
  const receiveDocumentFileChanged = vi.fn(() => true);
  const fake = fakeRunStream();
  const vueScope = effectScope();
  vueScope.run(() => useWorkbenchDocumentRunIntegration({
    userId,
    workbenchId,
    phase,
    conversationGeneration,
    activeRunId,
    archived,
    documentEventScope,
    receiveDocumentFileChanged,
    runStreamFactory: fake.factory,
  }));
  return {
    vueScope,
    userId,
    workbenchId,
    phase,
    conversationGeneration,
    activeRunId,
    archived,
    documentEventScope,
    receiveDocumentFileChanged,
    fake,
  };
}

function changedState(
  context: WorkbenchRunContext,
  eventId: number,
  data: {
    repositoryKey: string;
    path: string;
    changeType: string;
    contentVersion: string;
  },
  state = createWorkbenchRunState(context),
): WorkbenchRunState {
  return applyWorkbenchRunEvent(state, {
    id: eventId,
    type: 'file_changed',
    data: JSON.stringify({
      schemaVersion: 'workbench-run-event@1',
      ...context,
      occurredAt: eventId * 100,
      data,
    }),
  }, context);
}

describe('useWorkbenchDocumentRunIntegration', () => {
  it('captures the document token at subscription time and maps FILE_CHANGED exactly once', () => {
    const setup = createSetup();
    const capturedScope = setup.documentEventScope.value;
    const context = {
      workbenchId: 'workbench-1',
      phase: 'IMPLEMENT_TEST',
      runId: 'run-active',
    } as const;

    expect(setup.fake.identity.value).toEqual({
      userId: 'user-1',
      workbenchId: 'workbench-1',
      phase: 'IMPLEMENT_TEST',
      conversationGeneration: 2,
    });
    expect(setup.fake.stream.resume).toHaveBeenCalledWith('run-active');
    expect(setup.fake.stream.attach).toHaveBeenCalledWith('run-active');

    const projected = changedState(context, 7, {
      repositoryKey: 'Agent-Web',
      path: 'docs/Design.MD',
      changeType: 'DELETED',
      contentVersion: 'sha256:v7',
    });
    setup.fake.stream.state.value = projected;

    expect(setup.receiveDocumentFileChanged).toHaveBeenCalledWith(capturedScope, {
      repositoryKey: 'Agent-Web',
      relativePath: 'docs/Design.MD',
      changeType: 'DELETED',
      contentVersion: 'sha256:v7',
    });

    setup.fake.stream.state.value = { ...projected };
    expect(setup.receiveDocumentFileChanged).toHaveBeenCalledTimes(1);
    setup.vueScope.stop();
  });

  it('sorts newly observed file projections by event id', () => {
    const setup = createSetup();
    const context = {
      workbenchId: 'workbench-1',
      phase: 'IMPLEMENT_TEST',
      runId: 'run-active',
    } as const;
    let projected = changedState(context, 8, {
      repositoryKey: 'repo-b',
      path: 'b.md',
      changeType: 'MODIFIED',
      contentVersion: 'v8',
    });
    projected = changedState(context, 9, {
      repositoryKey: 'repo-a',
      path: 'a.md',
      changeType: 'MODIFIED',
      contentVersion: 'v9',
    }, projected);
    setup.fake.stream.state.value = {
      ...projected,
      staleDocuments: [...projected.staleDocuments].reverse(),
    };

    expect(setup.receiveDocumentFileChanged.mock.calls.map(([, event]) => event.relativePath))
      .toEqual(['b.md', 'a.md']);
    setup.vueScope.stop();
  });

  it('rejects old run context and delayed phase events after rebuilding the subscription', async () => {
    const setup = createSetup();
    const oldContext = {
      workbenchId: 'workbench-1',
      phase: 'IMPLEMENT_TEST',
      runId: 'run-active',
    } as const;

    setup.phase.value = 'SOLUTION_DESIGN';
    setup.documentEventScope.value = documentScope('workbench-1', 'SOLUTION_DESIGN', 2);
    await nextTick();

    expect(setup.fake.stream.close).toHaveBeenCalled();
    expect(setup.fake.identity.value?.phase).toBe('SOLUTION_DESIGN');
    setup.fake.stream.state.value = changedState(oldContext, 11, {
      repositoryKey: 'agent-web',
      path: 'docs/old.md',
      changeType: 'MODIFIED',
      contentVersion: 'v11',
    });
    expect(setup.receiveDocumentFileChanged).not.toHaveBeenCalled();

    const newContext = {
      workbenchId: 'workbench-1',
      phase: 'SOLUTION_DESIGN',
      runId: 'run-active',
    } as const;
    setup.fake.stream.state.value = changedState(newContext, 1, {
      repositoryKey: 'agent-web',
      path: 'docs/new.md',
      changeType: 'MODIFIED',
      contentVersion: 'v1',
    });
    expect(setup.receiveDocumentFileChanged).toHaveBeenLastCalledWith(
      setup.documentEventScope.value,
      expect.objectContaining({ relativePath: 'docs/new.md' }),
    );
    setup.vueScope.stop();
  });

  it('rejects delayed workbench events after a workbench scope switch', () => {
    const setup = createSetup();
    const oldContext = {
      workbenchId: 'workbench-1',
      phase: 'IMPLEMENT_TEST',
      runId: 'run-active',
    } as const;

    setup.workbenchId.value = 'workbench-2';
    setup.documentEventScope.value = documentScope('workbench-2', 'IMPLEMENT_TEST', 2);
    expect(setup.fake.identity.value?.workbenchId).toBe('workbench-2');

    setup.fake.stream.state.value = changedState(oldContext, 13, {
      repositoryKey: 'agent-web',
      path: 'docs/old-workbench.md',
      changeType: 'MODIFIED',
      contentVersion: 'v13',
    });
    expect(setup.receiveDocumentFileChanged).not.toHaveBeenCalled();

    const newContext = { ...oldContext, workbenchId: 'workbench-2' };
    setup.fake.stream.state.value = changedState(newContext, 1, {
      repositoryKey: 'agent-web',
      path: 'docs/new-workbench.md',
      changeType: 'MODIFIED',
      contentVersion: 'v1',
    });
    expect(setup.receiveDocumentFileChanged).toHaveBeenLastCalledWith(
      setup.documentEventScope.value,
      expect.objectContaining({ relativePath: 'docs/new-workbench.md' }),
    );
    setup.vueScope.stop();
  });

  it('rebuilds for conversation generation and active run changes, rejecting the old run state', () => {
    const setup = createSetup();
    const oldContext = {
      workbenchId: 'workbench-1',
      phase: 'IMPLEMENT_TEST',
      runId: 'run-active',
    } as const;

    setup.conversationGeneration.value = 3;
    expect(setup.fake.identity.value?.conversationGeneration).toBe(3);
    setup.activeRunId.value = 'run-new';
    expect(setup.fake.stream.resume).toHaveBeenLastCalledWith('run-new');
    expect(setup.fake.stream.attach).toHaveBeenLastCalledWith('run-new');

    setup.fake.stream.state.value = changedState(oldContext, 14, {
      repositoryKey: 'agent-web',
      path: 'docs/old-run.md',
      changeType: 'MODIFIED',
      contentVersion: 'v14',
    });
    expect(setup.receiveDocumentFileChanged).not.toHaveBeenCalled();

    const newContext = { ...oldContext, runId: 'run-new' };
    setup.fake.stream.state.value = changedState(newContext, 1, {
      repositoryKey: 'agent-web',
      path: 'docs/new-run.md',
      changeType: 'MODIFIED',
      contentVersion: 'v1',
    });
    expect(setup.receiveDocumentFileChanged).toHaveBeenCalledWith(
      setup.documentEventScope.value,
      expect.objectContaining({ relativePath: 'docs/new-run.md' }),
    );
    setup.vueScope.stop();
  });

  it('closes on archive and never projects delayed state into the archived scope', () => {
    const setup = createSetup();
    const context = {
      workbenchId: 'workbench-1',
      phase: 'IMPLEMENT_TEST',
      runId: 'run-active',
    } as const;

    setup.archived.value = true;
    expect(setup.fake.identity.value).toBeNull();
    setup.fake.stream.state.value = changedState(context, 3, {
      repositoryKey: 'agent-web',
      path: 'README.md',
      changeType: 'MODIFIED',
      contentVersion: 'v3',
    });
    expect(setup.receiveDocumentFileChanged).not.toHaveBeenCalled();
    setup.vueScope.stop();
  });

  it('rebinds when the document token changes and captures only the new token', () => {
    const setup = createSetup();
    const oldToken = setup.documentEventScope.value;
    const newToken = documentScope('workbench-1', 'IMPLEMENT_TEST', 9);
    setup.documentEventScope.value = newToken;
    const context = {
      workbenchId: 'workbench-1',
      phase: 'IMPLEMENT_TEST',
      runId: 'run-active',
    } as const;

    setup.fake.stream.state.value = changedState(context, 12, {
      repositoryKey: 'agent-web',
      path: 'docs/current.md',
      changeType: 'MODIFIED',
      contentVersion: 'v12',
    });

    expect(setup.receiveDocumentFileChanged).toHaveBeenCalledWith(
      newToken,
      expect.objectContaining({ relativePath: 'docs/current.md' }),
    );
    expect(setup.receiveDocumentFileChanged).not.toHaveBeenCalledWith(
      oldToken,
      expect.anything(),
    );
    setup.vueScope.stop();
  });

  it('does not resume an old marker when detail names a different active run', () => {
    const setup = createSetup();
    expect(setup.fake.stream.resume).toHaveBeenCalledWith('run-active');
    expect(setup.fake.stream.attach).toHaveBeenCalledWith('run-active');
    setup.vueScope.stop();
  });
});

describe('Workbench Document Run page integration', () => {
  it('projects FILE_CHANGED from the single conversation stream into the Document Pane', async () => {
    const page = await readFile(
      new URL('../../frontend/js/pages/Workbench.vue', import.meta.url),
      'utf8',
    );

    expect(page).not.toContain('useWorkbenchDocumentRunIntegration');
    expect(page).toMatch(
      /conversation\.runState\.value\?\.staleDocuments[\s\S]*?documentPane\.receiveDocumentFileChanged\(scope, \{[\s\S]*?repositoryKey: changed\.repositoryKey,[\s\S]*?relativePath: changed\.path,[\s\S]*?changeType: changed\.changeType,[\s\S]*?contentVersion: changed\.contentVersion,/,
    );
  });
});
