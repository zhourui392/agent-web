/**
 * Workbench Stage 冻结 Run Mode 的提交编排。
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

function runApi(): WorkbenchRunApiClient {
  return {
    getStageConversationMessages: vi.fn().mockResolvedValue({
      sessionId: null,
      generation: 0,
      workbenchVersion: 7,
      messages: [],
      nextCursor: null,
    }),
    ensureStageConversation: vi.fn(),
    restartStageConversation: vi.fn(),
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
    stopRun: vi.fn(),
    listRuns: vi.fn(),
    getRunEvents: vi.fn(),
    getRunCapability: vi.fn(),
    eventsUrl: vi.fn((workbenchId, runId) => `/events/${workbenchId}/${runId}`),
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

function conversation(
  apiClient: WorkbenchRunApiClient,
  allowedRunModes: 'DISCUSS_READ_ONLY'[] | ['DISCUSS_READ_ONLY', 'MODIFY_WORKSPACE'],
) {
  return useWorkbenchConversation({
    ownerId: ref('owner-1'),
    workbenchId: ref('workbench-1'),
    stageInstanceIdentifier: ref('stage-analysis'),
    conversationGeneration: ref(0),
    activeRunId: ref(null),
    expectedVersion: ref(7),
    stageStatus: ref('IN_PROGRESS'),
    allowedRunModes: ref(allowedRunModes),
    apiClient,
    stream: runStream(),
  });
}

describe('useWorkbenchConversation Stage Run Mode', () => {
  it('automatically uses the only Run Mode frozen in the Stage Snapshot', async () => {
    const apiClient = runApi();
    const state = conversation(apiClient, ['DISCUSS_READ_ONLY']);

    state.updateComposerText('分析这个问题');
    expect(state.selectedRunMode.value).toBe('DISCUSS_READ_ONLY');
    expect(state.conversationCanSubmit.value).toBe(true);
    await state.submitConversation();

    expect(apiClient.submitRun).toHaveBeenCalledWith(expect.objectContaining({
      stageInstanceIdentifier: 'stage-analysis',
      request: { message: '分析这个问题', runMode: 'DISCUSS_READ_ONLY' },
    }));
  });

  it('automatically selects the writable Run Mode when both modes are allowed', async () => {
    const apiClient = runApi();
    const state = conversation(
      apiClient,
      ['DISCUSS_READ_ONLY', 'MODIFY_WORKSPACE'],
    );

    state.updateComposerText('执行任务');
    expect(state.selectedRunMode.value).toBe('MODIFY_WORKSPACE');
    expect(state.conversationCanSubmit.value).toBe(true);
    await state.submitConversation();

    expect(apiClient.submitRun).toHaveBeenCalledWith(expect.objectContaining({
      request: { message: '执行任务', runMode: 'MODIFY_WORKSPACE' },
    }));
    expect(state.selectRunMode('INVALID' as 'MODIFY_WORKSPACE')).toBe(false);
  });
});
