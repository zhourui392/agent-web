/**
 * Workbench Phase Run submit/stream/stop orchestration.
 *
 * @author alex
 * @since 2026-08-01
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
    ensureConversation: vi.fn().mockResolvedValue({
      sessionId: 'session-1',
      generation: 0,
      workbenchVersion: 7,
      created: false,
    }),
    submitRun: vi.fn().mockResolvedValue({
      runId: 'run-1',
      sessionId: 'session-1',
      status: 'PENDING',
      phaseStatus: 'IN_PROGRESS',
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

function stream(): UseWorkbenchRunStream {
  return {
    state: shallowRef(null),
    error: shallowRef(null),
    connectionStatus: shallowRef('idle'),
    attach: vi.fn().mockReturnValue(true),
    resume: vi.fn().mockReturnValue(false),
    close: vi.fn(),
  };
}

describe('useWorkbenchConversation', () => {
  it('lazily ensures the first Phase conversation before submitting with the updated version', async () => {
    const api = runApi();
    api.ensureConversation = vi.fn().mockResolvedValue({
      sessionId: 'phase-session-1',
      generation: 0,
      workbenchVersion: 8,
      created: true,
    });
    const ensured = vi.fn();
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('REQUIREMENT_ANALYSIS'),
      conversationGeneration: ref(0),
      currentConversationId: ref(null),
      activeRunId: ref(null),
      expectedVersion: ref(7),
      handoffRequired: ref(false),
      handoffSourceVersion: ref(null),
      reviewConfirmationId: ref(null),
      apiClient: api,
      stream: stream(),
      onConversationEnsured: ensured,
    });
    conversation.updateComposerText('核实首次阶段需求');

    await conversation.submitConversation();

    expect(api.ensureConversation).toHaveBeenCalledWith(
      'wb-1', 'REQUIREMENT_ANALYSIS', 7,
    );
    expect(api.submitRun).toHaveBeenCalledWith(expect.objectContaining({
      expectedVersion: 8,
    }));
    expect(ensured).toHaveBeenCalledWith({
      sessionId: 'phase-session-1',
      generation: 0,
      workbenchVersion: 8,
      created: true,
    });
  });

  it('submits a Review modify Run only with the exact confirmation and accepted Handoff version', async () => {
    const api = runApi();
    const runtime = stream();
    const submitted = vi.fn();
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('REVIEW_REFACTOR'),
      conversationGeneration: ref(2),
      activeRunId: ref(null),
      expectedVersion: ref(7),
      archived: ref(false),
      handoffRequired: ref(true),
      handoffSourceVersion: ref(4),
      reviewConfirmationId: ref('confirmation-7'),
      apiClient: api,
      stream: runtime,
      onSubmitted: submitted,
    });
    conversation.updateRunMode('MODIFY_WORKSPACE');
    conversation.updateComposerText('按此意见提取领域策略并运行受影响测试');

    await conversation.submitConversation();

    expect(api.submitRun).toHaveBeenCalledWith(expect.objectContaining({
      workbenchId: 'wb-1',
      phase: 'REVIEW_REFACTOR',
      expectedVersion: 7,
      idempotencyKey: expect.any(String),
      request: {
        message: '按此意见提取领域策略并运行受影响测试',
        runMode: 'MODIFY_WORKSPACE',
        handoffSourceVersion: 4,
        reviewConfirmationId: 'confirmation-7',
      },
    }));
    expect(runtime.attach).toHaveBeenCalledWith('run-1', 0);
    expect(submitted).toHaveBeenCalled();
    expect(conversation.composerText.value).toBe('');
  });

  it('fails closed before transport when Handoff or Review confirmation is missing', async () => {
    const api = runApi();
    const handoffSourceVersion = ref<number | null>(null);
    const confirmationId = ref<string | null>(null);
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('REVIEW_REFACTOR'),
      conversationGeneration: ref(1),
      activeRunId: ref(null),
      expectedVersion: ref(3),
      handoffRequired: ref(true),
      handoffSourceVersion,
      reviewConfirmationId: confirmationId,
      apiClient: api,
      stream: stream(),
    });
    conversation.updateRunMode('MODIFY_WORKSPACE');
    conversation.updateComposerText('执行重构');

    await conversation.submitConversation();
    expect(api.submitRun).not.toHaveBeenCalled();
    expect(conversation.conversationError.value).toContain('交接');

    handoffSourceVersion.value = 2;
    await conversation.submitConversation();
    expect(api.submitRun).not.toHaveBeenCalled();
    expect(conversation.conversationError.value).toContain('Review');
  });

  it('keeps archived Run history readable but prevents submit and stop', async () => {
    const api = runApi();
    const runtime = stream();
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('IMPLEMENT_TEST'),
      conversationGeneration: ref(1),
      activeRunId: ref('run-1'),
      expectedVersion: ref(3),
      archived: ref(true),
      handoffRequired: ref(false),
      handoffSourceVersion: ref(null),
      reviewConfirmationId: ref(null),
      apiClient: api,
      stream: runtime,
    });
    conversation.updateComposerText('禁止发送');
    await conversation.submitConversation();
    await conversation.stopConversation();

    expect(runtime.resume).toHaveBeenCalledWith('run-1');
    expect(api.submitRun).not.toHaveBeenCalled();
    expect(api.stopRun).not.toHaveBeenCalled();
    expect(conversation.conversationReadOnly.value).toBe(true);
  });

  it('does not start a Run when authenticated owner identity is unavailable for SSE isolation', async () => {
    const api = runApi();
    const conversation = useWorkbenchConversation({
      ownerId: ref(''),
      workbenchId: ref('wb-1'),
      phase: ref('REQUIREMENT_ANALYSIS'),
      conversationGeneration: ref(1),
      activeRunId: ref(null),
      expectedVersion: ref(3),
      handoffRequired: ref(false),
      handoffSourceVersion: ref(null),
      reviewConfirmationId: ref(null),
      apiClient: api,
      stream: stream(),
    });
    conversation.updateComposerText('核实需求');

    await conversation.submitConversation();

    expect(api.submitRun).not.toHaveBeenCalled();
    expect(conversation.identityReady.value).toBe(false);
    expect(conversation.conversationError.value).toContain('身份');
  });

  it('sends stop through the persisted active Run and waits for terminal state', async () => {
    const api = runApi();
    const runtime = stream();
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('IMPLEMENT_TEST'),
      conversationGeneration: ref(1),
      activeRunId: ref('run-1'),
      expectedVersion: ref(3),
      archived: ref(false),
      handoffRequired: ref(false),
      handoffSourceVersion: ref(null),
      reviewConfirmationId: ref(null),
      apiClient: api,
      stream: runtime,
    });

    await conversation.stopConversation();

    expect(api.stopRun).toHaveBeenCalledWith('wb-1', 'run-1');
    expect(conversation.stopping.value).toBe(false);
    expect(conversation.conversationNotice.value).toContain('终态');
  });
});
