/**
 * Workbench Stage Conversation 提交、恢复与停止编排。
 *
 * @author alex
 * @since 2026-08-05
 */
// @ts-expect-error Frontend Vue ESM entry has no declaration for this relative test import.
import * as frontendVueRuntime from '../../frontend/node_modules/vue/index.mjs';
import { describe, expect, it, vi } from 'vitest';
import type { WorkbenchRunApiClient } from '../../frontend/js/api/workbench-run.js';
import { useWorkbenchConversation } from '../../frontend/js/composables/useWorkbenchConversation.js';
import type { UseWorkbenchRunStream } from '../../frontend/js/composables/useWorkbenchRunStream.js';

const { ref, shallowRef } = frontendVueRuntime as typeof import('vue');

function runApi(overrides: Partial<WorkbenchRunApiClient> = {}): WorkbenchRunApiClient {
  return {
    getStageConversationMessages: vi.fn().mockResolvedValue({
      sessionId: 'stage-session-1',
      generation: 0,
      workbenchVersion: 7,
      messages: [],
      nextCursor: null,
    }),
    ensureStageConversation: vi.fn().mockResolvedValue({
      sessionId: 'stage-session-1',
      generation: 0,
      workbenchVersion: 7,
      created: false,
    }),
    restartStageConversation: vi.fn().mockResolvedValue({
      sessionId: 'stage-session-2',
      previousSessionId: 'stage-session-1',
      generation: 1,
      workbenchVersion: 8,
      replayed: false,
    }),
    submitRun: vi.fn().mockResolvedValue({
      runId: 'run-1',
      sessionId: 'stage-session-1',
      status: 'PENDING',
      stageStatus: 'IN_PROGRESS',
      workbenchVersion: 8,
      capabilitySnapshotHash: 'a'.repeat(64),
      repositoryScopeHash: 'b'.repeat(64),
      replayed: false,
    }),
    getRun: vi.fn(),
    stopRun: vi.fn().mockResolvedValue({ runId: 'run-1', status: 'CANCEL_REQUESTED' }),
    listRuns: vi.fn(),
    getRunEvents: vi.fn(),
    getRunCapability: vi.fn(),
    eventsUrl: vi.fn((workbenchId, runId) => `/events/${workbenchId}/${runId}`),
    ...overrides,
  };
}

function runStream(): UseWorkbenchRunStream {
  return {
    state: shallowRef(null),
    error: shallowRef(null),
    connectionStatus: shallowRef('idle'),
    attach: vi.fn().mockReturnValue(true),
    resume: vi.fn().mockReturnValue(false),
    close: vi.fn(),
  };
}

function createConversation(
  apiClient: WorkbenchRunApiClient,
  overrides: Record<string, unknown> = {},
) {
  return useWorkbenchConversation({
    ownerId: ref('owner-1'),
    workbenchId: ref<string | null>('workbench-1'),
    stageInstanceIdentifier: ref<string | null>('stage-analysis'),
    conversationGeneration: ref(0),
    currentConversationId: ref<string | null>('stage-session-1'),
    activeRunId: ref<string | null>(null),
    expectedVersion: ref<number | null>(7),
    allowedRunModes: ref(['DISCUSS_READ_ONLY'] as const),
    stageStatus: ref('IN_PROGRESS' as const),
    apiClient,
    stream: runStream(),
    ...overrides,
  });
}

describe('useWorkbenchConversation', () => {
  it('loads messages only by the selected Stage instance identity', async () => {
    const apiClient = runApi({
      getStageConversationMessages: vi.fn().mockResolvedValue({
        sessionId: 'stage-session-1',
        generation: 0,
        workbenchVersion: 7,
        messages: [{
          messageId: 31,
          role: 'assistant',
          content: 'Stage message',
          timestamp: '2026-08-05T00:00:00Z',
          runId: null,
        }],
        nextCursor: null,
      }),
    });
    const state = createConversation(apiClient);

    await vi.waitFor(() => expect(state.conversationMessages.value).toHaveLength(1));

    expect(apiClient.getStageConversationMessages).toHaveBeenCalledWith(
      'workbench-1', 'stage-analysis',
    );
    expect(state.conversationMessages.value[0]?.content).toBe('Stage message');
  });

  it('ignores a late message response after the selected Stage changes', async () => {
    let resolveMessages: (value: unknown) => void = () => undefined;
    const pending = new Promise(resolve => { resolveMessages = resolve; });
    const stageInstanceIdentifier = ref<string | null>('stage-analysis');
    const apiClient = runApi({
      getStageConversationMessages: vi.fn()
        .mockReturnValueOnce(pending)
        .mockResolvedValueOnce({
          sessionId: 'stage-session-1', generation: 0, workbenchVersion: 7,
          messages: [], nextCursor: null,
        }),
    });
    const state = createConversation(apiClient, { stageInstanceIdentifier });

    stageInstanceIdentifier.value = 'stage-implementation';
    resolveMessages({
      sessionId: 'stage-session-1', generation: 0, workbenchVersion: 7,
      messages: [{
        messageId: 1, role: 'assistant', content: 'stale',
        timestamp: '2026-08-05T00:00:00Z', runId: null,
      }],
      nextCursor: null,
    });

    await vi.waitFor(() => expect(apiClient.getStageConversationMessages)
      .toHaveBeenCalledWith('workbench-1', 'stage-implementation'));
    expect(state.conversationMessages.value).toEqual([]);
  });

  it('ensures a missing Stage conversation before submitting the Run', async () => {
    const apiClient = runApi({
      ensureStageConversation: vi.fn().mockResolvedValue({
        sessionId: 'stage-session-created', generation: 0,
        workbenchVersion: 8, created: true,
      }),
    });
    const onConversationEnsured = vi.fn();
    const state = createConversation(apiClient, {
      currentConversationId: ref<string | null>(null),
      onConversationEnsured,
    });

    state.updateComposerText('analyze request');
    await state.submitConversation();

    expect(apiClient.ensureStageConversation).toHaveBeenCalledWith(
      'workbench-1', 'stage-analysis', 7,
    );
    expect(apiClient.submitRun).toHaveBeenCalledWith(expect.objectContaining({
      workbenchId: 'workbench-1',
      stageInstanceIdentifier: 'stage-analysis',
      expectedVersion: 8,
      request: { message: 'analyze request', runMode: 'DISCUSS_READ_ONLY' },
    }));
    expect(onConversationEnsured).toHaveBeenCalledWith(expect.objectContaining({
      sessionId: 'stage-session-created',
    }));
  });

  it('reuses the idempotency key only for an unchanged failed submission', async () => {
    const submitRun = vi.fn()
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce({
        runId: 'run-1', sessionId: 'stage-session-1', status: 'PENDING',
        stageStatus: 'IN_PROGRESS', workbenchVersion: 8,
        capabilitySnapshotHash: 'a'.repeat(64),
        repositoryScopeHash: 'b'.repeat(64), replayed: false,
      });
    const apiClient = runApi({ submitRun });
    const state = createConversation(apiClient);

    state.updateComposerText('same request');
    await state.submitConversation();
    await state.submitConversation();

    expect(submitRun).toHaveBeenCalledTimes(2);
    expect(submitRun.mock.calls[0][0].idempotencyKey)
      .toBe(submitRun.mock.calls[1][0].idempotencyKey);
  });

  it('restarts only the current Stage conversation with exact version binding', async () => {
    const apiClient = runApi();
    const restarted = vi.fn();
    const state = createConversation(apiClient, {
      onConversationRestarted: restarted,
    });

    expect(state.canRestartConversation.value).toBe(true);
    await state.restartConversation();

    expect(apiClient.restartStageConversation).toHaveBeenCalledWith(
      'workbench-1', 'stage-analysis', 7, expect.any(String),
    );
    expect(restarted).toHaveBeenCalledWith(expect.objectContaining({
      sessionId: 'stage-session-2',
      previousSessionId: 'stage-session-1',
    }));
  });

  it('stops the exact active Run and waits for an explicit terminal event', async () => {
    const apiClient = runApi();
    const state = createConversation(apiClient, {
      activeRunId: ref<string | null>('run-active'),
    });

    await state.stopConversation();

    expect(apiClient.stopRun).toHaveBeenCalledWith('workbench-1', 'run-active');
    expect(state.conversationNotice.value).toContain('停止请求已记录');
  });

  it('clears mutable composer state when the Stage identity changes', async () => {
    const stageInstanceIdentifier = ref<string | null>('stage-analysis');
    const state = createConversation(runApi(), { stageInstanceIdentifier });
    state.updateComposerText('temporary input');
    expect(state.addAttachment({
      repositoryKey: 'agent-web',
      relativePath: 'README.md',
      contentHash: 'a'.repeat(64),
    })).toBe(true);

    stageInstanceIdentifier.value = 'stage-implementation';

    expect(state.composerText.value).toBe('');
    expect(state.pendingAttachments.value).toEqual([]);
  });

  it('keeps archived Workbenches read-only', async () => {
    const apiClient = runApi();
    const state = createConversation(apiClient, { archived: ref(true) });

    state.updateComposerText('must not submit');
    await state.submitConversation();

    expect(state.conversationReadOnly.value).toBe(true);
    expect(apiClient.submitRun).not.toHaveBeenCalled();
  });
});
