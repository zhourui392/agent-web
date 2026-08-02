/**
 * Workbench Phase Run submit/stream/stop orchestration.
 *
 * @author alex
 * @since 2026-08-01
 */
// @ts-expect-error Frontend Vue ESM entry has no declaration for this relative test import.
import * as frontendVueRuntime from '../../frontend/node_modules/vue/index.mjs';
import { describe, expect, it, vi } from 'vitest';
import { WorkbenchRunApiError } from '../../frontend/js/api/workbench-run.js';
import type { WorkbenchRunApiClient } from '../../frontend/js/api/workbench-run.js';
import { useWorkbenchConversation } from '../../frontend/js/composables/useWorkbenchConversation.js';
import type { UseWorkbenchRunStream } from '../../frontend/js/composables/useWorkbenchRunStream.js';

const { ref, shallowRef } = frontendVueRuntime as typeof import('vue');

function runApi(overrides: Partial<WorkbenchRunApiClient> = {}): WorkbenchRunApiClient {
  return {
    getConversationMessages: vi.fn().mockResolvedValue({
      sessionId: 'session-1',
      generation: 0,
      workbenchVersion: 7,
      messages: [],
      nextCursor: null,
    }),
    ensureConversation: vi.fn().mockResolvedValue({
      sessionId: 'session-1',
      generation: 0,
      workbenchVersion: 7,
      created: false,
    }),
    restartConversation: vi.fn().mockResolvedValue({
      sessionId: 'session-2',
      previousSessionId: 'session-1',
      generation: 1,
      workbenchVersion: 8,
      replayed: false,
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
  it('blocks a second MODIFY run from another Phase while still allowing a read-only Run', async () => {
    const api = runApi();
    const activeWriteRunId = ref<string | null>('write-run-in-another-phase');
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('IMPLEMENT_TEST'),
      conversationGeneration: ref(1),
      currentConversationId: ref('session-1'),
      activeRunId: ref(null),
      activeWriteRunId,
      expectedVersion: ref(7),
      phaseStatus: ref('IN_PROGRESS'),
      handoffRequired: ref(false),
      handoffSourceVersion: ref(null),
      reviewConfirmationId: ref(null),
      apiClient: api,
      stream: stream(),
    });
    conversation.updateComposerText('先继续只读分析');

    expect(conversation.runMode.value).toBe('MODIFY_WORKSPACE');
    expect(conversation.writeRunBlocked.value).toBe(true);
    expect(conversation.conversationCanSubmit.value).toBe(false);

    await conversation.submitConversation();

    expect(api.submitRun).not.toHaveBeenCalled();
    expect(conversation.conversationError.value)
      .toBe('当前 Workbench 已有活动写 Run；可切换为只读讨论，或等待写 Run 进入终态。');

    conversation.updateRunMode('DISCUSS_READ_ONLY');
    expect(conversation.writeRunBlocked.value).toBe(false);
    expect(conversation.conversationCanSubmit.value).toBe(true);

    await conversation.submitConversation();

    expect(api.submitRun).toHaveBeenCalledWith(expect.objectContaining({
      request: expect.objectContaining({ runMode: 'DISCUSS_READ_ONLY' }),
    }));
  });

  it('loads the complete persisted messages for the current owner Workbench Phase identity', async () => {
    const api = runApi({
      getConversationMessages: vi.fn().mockResolvedValue({
        sessionId: 'session-1',
        generation: 2,
        workbenchVersion: 7,
        messages: [
          {
            messageId: 10,
            role: 'user',
            content: '请解释设计',
            timestamp: '2026-08-01T00:00:00Z',
            runId: 'run-1',
          },
          {
            messageId: 11,
            role: 'assistant',
            content: '## 方案',
            timestamp: '2026-08-01T00:00:01Z',
            runId: 'run-1',
          },
        ],
        nextCursor: 10,
      }),
    });
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('SOLUTION_DESIGN'),
      conversationGeneration: ref(2),
      currentConversationId: ref('session-1'),
      activeRunId: ref(null),
      expectedVersion: ref(7),
      phaseStatus: ref('IN_PROGRESS'),
      handoffRequired: ref(true),
      handoffSourceVersion: ref(1),
      reviewConfirmationId: ref(null),
      apiClient: api,
      stream: stream(),
    });

    await vi.waitFor(() => expect(conversation.conversationMessages.value).toHaveLength(2));

    expect(api.getConversationMessages).toHaveBeenCalledWith('wb-1', 'SOLUTION_DESIGN');
    expect(conversation.conversationMessages.value[1]).toMatchObject({
      role: 'assistant',
      content: '## 方案',
    });
    expect(conversation.messagesLoading.value).toBe(false);
    expect(conversation.hasOlderConversationMessages.value).toBe(true);
  });

  it('loads older persisted messages as bounded cursor pages without duplicates', async () => {
    const api = runApi({
      getConversationMessages: vi.fn()
        .mockResolvedValueOnce({
          sessionId: 'session-1',
          generation: 2,
          workbenchVersion: 7,
          messages: [{
            messageId: 10,
            role: 'assistant',
            content: '最新回答',
            timestamp: '2026-08-01T00:00:02Z',
            runId: 'run-2',
          }],
          nextCursor: 10,
        })
        .mockResolvedValueOnce({
          sessionId: 'session-1',
          generation: 2,
          workbenchVersion: 7,
          messages: [{
            messageId: 4,
            role: 'user',
            content: '更早问题',
            timestamp: '2026-08-01T00:00:00Z',
            runId: 'run-1',
          }, {
            messageId: 5,
            role: 'assistant',
            content: '更早回答',
            timestamp: '2026-08-01T00:00:01Z',
            runId: 'run-1',
          }],
          nextCursor: null,
        }),
    });
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('SOLUTION_DESIGN'),
      conversationGeneration: ref(2),
      currentConversationId: ref('session-1'),
      activeRunId: ref(null),
      expectedVersion: ref(7),
      phaseStatus: ref('IN_PROGRESS'),
      handoffRequired: ref(true),
      handoffSourceVersion: ref(1),
      reviewConfirmationId: ref(null),
      apiClient: api,
      stream: stream(),
    });
    await vi.waitFor(() => expect(conversation.conversationMessages.value).toHaveLength(1));

    await conversation.loadOlderConversationMessages();

    expect(api.getConversationMessages).toHaveBeenNthCalledWith(
      2, 'wb-1', 'SOLUTION_DESIGN', 10,
    );
    expect(conversation.conversationMessages.value.map(message => message.messageId))
      .toEqual([4, 5, 10]);
    expect(conversation.hasOlderConversationMessages.value).toBe(false);
    expect(conversation.olderMessagesLoading.value).toBe(false);
  });

  it('discards an old message response after owner Workbench Phase or generation changes', async () => {
    let resolveOld: (value: unknown) => void = () => undefined;
    const api = runApi({
      getConversationMessages: vi.fn()
        .mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve; }))
        .mockResolvedValueOnce({
          sessionId: 'session-new',
          generation: 3,
          workbenchVersion: 9,
          messages: [{
            messageId: 20,
            role: 'assistant',
            content: '新阶段消息',
            timestamp: '2026-08-01T00:00:02Z',
            runId: 'run-new',
          }],
        }),
    });
    const ownerId = ref('owner-1');
    const workbenchId = ref<string | null>('wb-1');
    const phase = ref<'REQUIREMENT_ANALYSIS' | 'SOLUTION_DESIGN'>('REQUIREMENT_ANALYSIS');
    const generation = ref(1);
    const currentConversationId = ref<string | null>('session-old');
    const conversation = useWorkbenchConversation({
      ownerId,
      workbenchId,
      phase,
      conversationGeneration: generation,
      currentConversationId,
      activeRunId: ref(null),
      expectedVersion: ref(8),
      phaseStatus: ref('IN_PROGRESS'),
      handoffRequired: ref(false),
      handoffSourceVersion: ref(null),
      reviewConfirmationId: ref(null),
      apiClient: api,
      stream: stream(),
    });

    phase.value = 'SOLUTION_DESIGN';
    generation.value = 3;
    currentConversationId.value = 'session-new';
    await vi.waitFor(() => expect(conversation.conversationMessages.value[0]?.content)
      .toBe('新阶段消息'));
    resolveOld({
      sessionId: 'session-old',
      generation: 1,
      workbenchVersion: 8,
      messages: [{
        messageId: 1,
        role: 'assistant',
        content: '不应污染的新旧消息',
        timestamp: '2026-08-01T00:00:00Z',
        runId: 'run-old',
      }],
    });
    await Promise.resolve();

    expect(conversation.conversationMessages.value.map(message => message.content))
      .toEqual(['新阶段消息']);
  });

  it('refreshes persisted messages after submit and terminal', async () => {
    const runtime = stream();
    const api = runApi({
      getConversationMessages: vi.fn()
        .mockResolvedValueOnce({
          sessionId: 'session-1', generation: 1, workbenchVersion: 7, messages: [],
        })
        .mockResolvedValueOnce({
          sessionId: 'session-1',
          generation: 1,
          workbenchVersion: 8,
          messages: [{
            messageId: 1,
            role: 'user',
            content: '提交后刷新',
            timestamp: '2026-08-01T00:00:00Z',
            runId: 'run-1',
          }],
        })
        .mockResolvedValueOnce({
          sessionId: 'session-1',
          generation: 1,
          workbenchVersion: 8,
          messages: [
            {
              messageId: 1,
              role: 'user',
              content: '提交后刷新',
              timestamp: '2026-08-01T00:00:00Z',
              runId: 'run-1',
            },
            {
              messageId: 2,
              role: 'assistant',
              content: '终态后刷新',
              timestamp: '2026-08-01T00:00:01Z',
              runId: 'run-1',
            },
          ],
        }),
    });
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('IMPLEMENT_TEST'),
      conversationGeneration: ref(1),
      currentConversationId: ref('session-1'),
      activeRunId: ref(null),
      expectedVersion: ref(7),
      phaseStatus: ref('IN_PROGRESS'),
      handoffRequired: ref(false),
      handoffSourceVersion: ref(null),
      reviewConfirmationId: ref(null),
      apiClient: api,
      stream: runtime,
    });
    await vi.waitFor(() => expect(api.getConversationMessages).toHaveBeenCalledTimes(1));
    conversation.updateComposerText('提交后刷新');

    await conversation.submitConversation();
    expect(conversation.conversationMessages.value.map(message => message.content))
      .toEqual(['提交后刷新']);

    runtime.state.value = {
      context: { workbenchId: 'wb-1', phase: 'IMPLEMENT_TEST', runId: 'run-1' },
      status: 'SUCCEEDED',
      runMode: 'MODIFY_WORKSPACE',
      lastAppliedEventSeq: 2,
      blocks: [],
      staleDocuments: [],
      testProgress: [],
      operations: [],
      terminal: {
        status: 'SUCCEEDED',
        failureCode: null,
        publicMessage: 'done',
        eventId: 2,
        occurredAt: Date.parse('2026-08-01T00:00:02Z'),
      },
    };
    await vi.waitFor(() => expect(conversation.conversationMessages.value).toHaveLength(2));
    expect(api.getConversationMessages).toHaveBeenCalledTimes(3);
  });

  it('restarts only an eligible active in-progress Phase and clears local Run and message state', async () => {
    const runtime = stream();
    runtime.state.value = {
      context: { workbenchId: 'wb-1', phase: 'SOLUTION_DESIGN', runId: 'run-old' },
      status: 'SUCCEEDED',
      runMode: 'DISCUSS_READ_ONLY',
      lastAppliedEventSeq: 1,
      blocks: [],
      staleDocuments: [],
      testProgress: [],
      operations: [],
      terminal: {
        status: 'SUCCEEDED',
        failureCode: null,
        publicMessage: 'done',
        eventId: 1,
        occurredAt: Date.parse('2026-08-01T00:00:01Z'),
      },
    };
    const generation = ref(1);
    const currentConversationId = ref<string | null>('session-1');
    const expectedVersion = ref<number | null>(7);
    const restarted = vi.fn(result => {
      currentConversationId.value = result.sessionId;
      generation.value = result.generation;
      expectedVersion.value = result.workbenchVersion;
    });
    const api = runApi({
      getConversationMessages: vi.fn()
        .mockResolvedValueOnce({
          sessionId: 'session-1',
          generation: 1,
          workbenchVersion: 7,
          messages: [{
            messageId: 1,
            role: 'user',
            content: '旧历史',
            timestamp: '2026-08-01T00:00:00Z',
            runId: 'run-old',
          }],
        })
        .mockResolvedValue({
          sessionId: 'session-2', generation: 2, workbenchVersion: 8, messages: [],
        }),
      restartConversation: vi.fn().mockResolvedValue({
        sessionId: 'session-2',
        previousSessionId: 'session-1',
        generation: 2,
        workbenchVersion: 8,
        replayed: false,
      }),
    });
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('SOLUTION_DESIGN'),
      conversationGeneration: generation,
      currentConversationId,
      activeRunId: ref(null),
      expectedVersion,
      phaseStatus: ref('IN_PROGRESS'),
      handoffRequired: ref(true),
      handoffSourceVersion: ref(1),
      reviewConfirmationId: ref(null),
      apiClient: api,
      stream: runtime,
      onConversationRestarted: restarted,
    });
    await vi.waitFor(() => expect(conversation.conversationMessages.value).toHaveLength(1));
    conversation.addAttachment({
      repositoryKey: 'agent-web',
      relativePath: 'README.md',
      contentHash: 'a'.repeat(64),
    });
    expect(conversation.canRestartConversation.value).toBe(true);

    await conversation.restartConversation();

    expect(api.restartConversation).toHaveBeenCalledWith(
      'wb-1', 'SOLUTION_DESIGN', 7, expect.any(String),
    );
    expect(restarted).toHaveBeenCalledWith(expect.objectContaining({
      sessionId: 'session-2', previousSessionId: 'session-1', generation: 2,
    }));
    expect(runtime.close).toHaveBeenCalled();
    expect(runtime.state.value).toBeNull();
    expect(conversation.conversationMessages.value).toEqual([]);
    expect(conversation.pendingAttachments.value).toEqual([]);
    expect(conversation.conversationNotice.value).toContain('旧历史只读保留');
    expect(conversation.restarting.value).toBe(false);
  });

  it('ignores a late restart response after the Workbench Phase identity changes', async () => {
    let resolveRestart: (value: unknown) => void = () => undefined;
    const restartResponse = new Promise(resolve => {
      resolveRestart = resolve;
    });
    const ownerId = ref('owner-1');
    const workbenchId = ref<string | null>('wb-1');
    const phase = ref<'REQUIREMENT_ANALYSIS' | 'SOLUTION_DESIGN'>('SOLUTION_DESIGN');
    const generation = ref(1);
    const currentConversationId = ref<string | null>('session-1');
    const expectedVersion = ref<number | null>(7);
    const runtime = stream();
    const restarted = vi.fn();
    const api = runApi({
      restartConversation: vi.fn().mockReturnValue(restartResponse),
    });
    const conversation = useWorkbenchConversation({
      ownerId,
      workbenchId,
      phase,
      conversationGeneration: generation,
      currentConversationId,
      activeRunId: ref(null),
      expectedVersion,
      phaseStatus: ref('IN_PROGRESS'),
      handoffRequired: ref(false),
      handoffSourceVersion: ref(null),
      reviewConfirmationId: ref(null),
      apiClient: api,
      stream: runtime,
      onConversationRestarted: restarted,
    });

    const restarting = conversation.restartConversation();
    workbenchId.value = 'wb-2';
    phase.value = 'REQUIREMENT_ANALYSIS';
    generation.value = 0;
    currentConversationId.value = 'session-other';
    expectedVersion.value = 3;
    await Promise.resolve();
    conversation.updateComposerText('新 Workbench 中不能丢的输入');
    conversation.addAttachment({
      repositoryKey: 'other-repository',
      relativePath: 'README.md',
      contentHash: 'b'.repeat(64),
    });

    resolveRestart({
      sessionId: 'session-2',
      previousSessionId: 'session-1',
      generation: 2,
      workbenchVersion: 8,
      replayed: false,
    });
    await restarting;

    expect(restarted).not.toHaveBeenCalled();
    expect(runtime.close).not.toHaveBeenCalled();
    expect(conversation.composerText.value).toBe('新 Workbench 中不能丢的输入');
    expect(conversation.pendingAttachments.value).toEqual([{
      repositoryKey: 'other-repository',
      relativePath: 'README.md',
      contentHash: 'b'.repeat(64),
    }]);
    expect(conversation.conversationNotice.value).toBeNull();
    expect(conversation.restarting.value).toBe(false);
  });

  it('disables restart outside an active in-progress idle Phase with a current session', () => {
    const archived = ref(false);
    const phaseStatus = ref<'NOT_STARTED' | 'IN_PROGRESS' | 'HUMAN_COMPLETED'>('NOT_STARTED');
    const currentConversationId = ref<string | null>(null);
    const activeRunId = ref<string | null>(null);
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('REQUIREMENT_ANALYSIS'),
      conversationGeneration: ref(0),
      currentConversationId,
      activeRunId,
      expectedVersion: ref(3),
      phaseStatus,
      archived,
      handoffRequired: ref(false),
      handoffSourceVersion: ref(null),
      reviewConfirmationId: ref(null),
      apiClient: runApi(),
      stream: stream(),
    });

    expect(conversation.canRestartConversation.value).toBe(false);
    currentConversationId.value = 'session-1';
    phaseStatus.value = 'IN_PROGRESS';
    expect(conversation.canRestartConversation.value).toBe(true);
    activeRunId.value = 'run-active';
    expect(conversation.canRestartConversation.value).toBe(false);
    activeRunId.value = null;
    archived.value = true;
    expect(conversation.canRestartConversation.value).toBe(false);
  });

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

  it('does not submit a late ensured conversation into a different Workbench Phase', async () => {
    let resolveEnsure: (value: unknown) => void = () => undefined;
    const ensureResponse = new Promise(resolve => {
      resolveEnsure = resolve;
    });
    const workbenchId = ref<string | null>('wb-1');
    const phase = ref<'REQUIREMENT_ANALYSIS' | 'IMPLEMENT_TEST'>('REQUIREMENT_ANALYSIS');
    const generation = ref(0);
    const currentConversationId = ref<string | null>(null);
    const expectedVersion = ref<number | null>(7);
    const ensured = vi.fn();
    const submitted = vi.fn();
    const api = runApi({
      ensureConversation: vi.fn().mockReturnValue(ensureResponse),
    });
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId,
      phase,
      conversationGeneration: generation,
      currentConversationId,
      activeRunId: ref(null),
      expectedVersion,
      phaseStatus: ref('IN_PROGRESS'),
      handoffRequired: ref(false),
      handoffSourceVersion: ref(1),
      reviewConfirmationId: ref(null),
      apiClient: api,
      stream: stream(),
      onConversationEnsured: ensured,
      onSubmitted: submitted,
    });
    conversation.updateComposerText('原需求阶段的只读问题');

    const submission = conversation.submitConversation();
    phase.value = 'IMPLEMENT_TEST';
    currentConversationId.value = 'implement-session';
    expectedVersion.value = 10;
    await Promise.resolve();
    conversation.updateComposerText('开发阶段的新输入');
    resolveEnsure({
      sessionId: 'analysis-session',
      generation: 0,
      workbenchVersion: 8,
      created: true,
    });
    await submission;

    expect(api.ensureConversation).toHaveBeenCalledWith(
      'wb-1', 'REQUIREMENT_ANALYSIS', 7,
    );
    expect(api.submitRun).not.toHaveBeenCalled();
    expect(ensured).not.toHaveBeenCalled();
    expect(submitted).not.toHaveBeenCalled();
    expect(conversation.composerText.value).toBe('开发阶段的新输入');
    expect(conversation.runMode.value).toBe('MODIFY_WORKSPACE');
    expect(conversation.submitting.value).toBe(false);
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

  it('submits strict pending attachments and clears them only after success', async () => {
    const api = runApi();
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('SOLUTION_DESIGN'),
      conversationGeneration: ref(1),
      currentConversationId: ref('session-1'),
      activeRunId: ref(null),
      expectedVersion: ref(7),
      phaseStatus: ref('IN_PROGRESS'),
      handoffRequired: ref(false),
      handoffSourceVersion: ref(null),
      reviewConfirmationId: ref(null),
      apiClient: api,
      stream: stream(),
    });
    const first = {
      repositoryKey: 'agent-web',
      relativePath: 'docs/design.md',
      contentHash: 'a'.repeat(64),
    };
    const second = {
      repositoryKey: 'shared-lib',
      relativePath: 'src/main/App.java',
      contentHash: 'b'.repeat(64),
    };
    conversation.addAttachment(first);
    conversation.addAttachment(second);
    conversation.updateComposerText('结合附件讨论方案');

    await conversation.submitConversation();

    expect(api.submitRun).toHaveBeenCalledWith(expect.objectContaining({
      request: {
        message: '结合附件讨论方案',
        runMode: 'DISCUSS_READ_ONLY',
        attachments: [first, second],
      },
    }));
    expect(conversation.pendingAttachments.value).toEqual([]);
  });

  it('keeps uploaded attachment preview metadata locally but submits only id and content hash', async () => {
    const api = runApi();
    const submitted = vi.fn();
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('REQUIREMENT_ANALYSIS'),
      conversationGeneration: ref(1),
      currentConversationId: ref('session-1'),
      activeRunId: ref(null),
      expectedVersion: ref(7),
      phaseStatus: ref('IN_PROGRESS'),
      handoffRequired: ref(false),
      handoffSourceVersion: ref(null),
      reviewConfirmationId: ref(null),
      apiClient: api,
      stream: stream(),
      onSubmitted: submitted,
    });
    const uploaded = {
      type: 'UPLOADED_CONVERSATION' as const,
      attachmentId: 'attachment-1',
      contentHash: 'c'.repeat(64),
      displayName: 'architecture.png',
      mediaType: 'image/png',
      size: 128,
      previewUrl: 'blob:local-preview',
    };

    expect(conversation.addAttachment(uploaded)).toBe(true);
    conversation.updateComposerText('结合图片分析需求');
    expect(conversation.pendingAttachments.value).toEqual([uploaded]);

    await conversation.submitConversation();

    const wireAttachment = {
      type: 'UPLOADED_CONVERSATION',
      attachmentId: 'attachment-1',
      contentHash: 'c'.repeat(64),
    };
    expect(api.submitRun).toHaveBeenCalledWith(expect.objectContaining({
      request: {
        message: '结合图片分析需求',
        runMode: 'DISCUSS_READ_ONLY',
        attachments: [wireAttachment],
      },
    }));
    expect(submitted).toHaveBeenCalledWith(
      expect.objectContaining({ runId: 'run-1' }),
      'DISCUSS_READ_ONLY',
      [wireAttachment],
    );
    expect(JSON.stringify(vi.mocked(api.submitRun).mock.calls[0][0]))
      .not.toMatch(/blob:|displayName|mediaType|size|repositoryKey|relativePath/);
    expect(conversation.pendingAttachments.value).toEqual([]);
  });

  it('enforces one combined attachment limit and distinct uploaded logical identities', () => {
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('REQUIREMENT_ANALYSIS'),
      conversationGeneration: ref(1),
      currentConversationId: ref('session-1'),
      activeRunId: ref(null),
      expectedVersion: ref(7),
      phaseStatus: ref('IN_PROGRESS'),
      handoffRequired: ref(false),
      handoffSourceVersion: ref(null),
      reviewConfirmationId: ref(null),
      apiClient: runApi(),
      stream: stream(),
    });
    for (let index = 0; index < 7; index += 1) {
      expect(conversation.addAttachment({
        repositoryKey: 'agent-web',
        relativePath: `docs/${index}.md`,
        contentHash: 'a'.repeat(64),
      })).toBe(true);
    }
    const uploaded = {
      type: 'UPLOADED_CONVERSATION' as const,
      attachmentId: 'attachment-1',
      contentHash: 'b'.repeat(64),
      displayName: 'notes.txt',
      mediaType: 'text/plain',
      size: 10,
      previewUrl: null,
    };
    expect(conversation.addAttachment(uploaded)).toBe(true);
    expect(conversation.addAttachment({ ...uploaded, contentHash: 'c'.repeat(64) })).toBe(false);
    expect(conversation.addAttachment({
      repositoryKey: 'agent-web',
      relativePath: 'docs/ninth.md',
      contentHash: 'd'.repeat(64),
    })).toBe(false);
    expect(conversation.pendingAttachments.value).toHaveLength(8);

    conversation.removeUploadedAttachment('attachment-1');

    expect(conversation.pendingAttachments.value).toHaveLength(7);
    expect(conversation.pendingAttachments.value)
      .not.toEqual(expect.arrayContaining([expect.objectContaining({ attachmentId: 'attachment-1' })]));
  });

  it('retains attachments on failure, reuses the same key, and changes the key after attachment change', async () => {
    const api = runApi({
      submitRun: vi.fn().mockRejectedValue(new Error('offline')),
    });
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('SOLUTION_DESIGN'),
      conversationGeneration: ref(1),
      currentConversationId: ref('session-1'),
      activeRunId: ref(null),
      expectedVersion: ref(7),
      phaseStatus: ref('IN_PROGRESS'),
      handoffRequired: ref(false),
      handoffSourceVersion: ref(null),
      reviewConfirmationId: ref(null),
      apiClient: api,
      stream: stream(),
    });
    const design = {
      repositoryKey: 'agent-web',
      relativePath: 'docs/design.md',
      contentHash: 'a'.repeat(64),
    };
    const readme = {
      repositoryKey: 'agent-web',
      relativePath: 'README.md',
      contentHash: 'b'.repeat(64),
    };
    conversation.addAttachment(design);
    conversation.updateComposerText('讨论失败后重试');

    await conversation.submitConversation();
    await conversation.submitConversation();

    const firstKey = vi.mocked(api.submitRun).mock.calls[0][0].idempotencyKey;
    const retryKey = vi.mocked(api.submitRun).mock.calls[1][0].idempotencyKey;
    expect(retryKey).toBe(firstKey);
    expect(conversation.pendingAttachments.value).toEqual([design]);

    conversation.removeAttachment('agent-web', 'docs/design.md');
    conversation.addAttachment(readme);
    conversation.updateComposerText('讨论失败后重试');
    await conversation.submitConversation();

    const changedKey = vi.mocked(api.submitRun).mock.calls[2][0].idempotencyKey;
    expect(changedKey).not.toBe(firstKey);
    expect(conversation.pendingAttachments.value).toEqual([readme]);
  });

  it.each([
    'WORKSPACE_TOPOLOGY_CHANGED',
    'WORKSPACE_REPOSITORY_NOT_FOUND',
    'WORKBENCH_REPOSITORY_SCOPE_INVALID',
  ])('shows an actionable recovery path when the frozen repository is unavailable: %s', async code => {
    const api = runApi({
      submitRun: vi.fn().mockRejectedValue(new WorkbenchRunApiError(
        409,
        code,
        'Workbench Run request conflicts with current state',
      )),
    });
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId: ref('wb-1'),
      phase: ref('IMPLEMENT_TEST'),
      conversationGeneration: ref(1),
      currentConversationId: ref('session-1'),
      activeRunId: ref(null),
      expectedVersion: ref(7),
      phaseStatus: ref('IN_PROGRESS'),
      handoffRequired: ref(false),
      handoffSourceVersion: ref(null),
      reviewConfirmationId: ref(null),
      apiClient: api,
      stream: stream(),
    });
    conversation.updateComposerText('继续当前任务');

    await conversation.submitConversation();

    expect(conversation.conversationError.value)
      .toBe('仓库目录已移动、消失或不再匹配冻结范围；请恢复原目录，或创建新的 Workbench。');
  });

  it('keeps attachments while editing text and clears them on identity or generation change', () => {
    const workbenchId = ref<string | null>('wb-1');
    const phase = ref<'REQUIREMENT_ANALYSIS' | 'SOLUTION_DESIGN'>('REQUIREMENT_ANALYSIS');
    const generation = ref(1);
    const conversation = useWorkbenchConversation({
      ownerId: ref('owner-1'),
      workbenchId,
      phase,
      conversationGeneration: generation,
      currentConversationId: ref('session-1'),
      activeRunId: ref(null),
      expectedVersion: ref(7),
      phaseStatus: ref('IN_PROGRESS'),
      handoffRequired: ref(false),
      handoffSourceVersion: ref(null),
      reviewConfirmationId: ref(null),
      apiClient: runApi(),
      stream: stream(),
    });
    const attachment = {
      repositoryKey: 'agent-web',
      relativePath: 'README.md',
      contentHash: 'a'.repeat(64),
    };

    conversation.addAttachment(attachment);
    conversation.updateComposerText('普通编辑');
    expect(conversation.pendingAttachments.value).toEqual([attachment]);

    generation.value = 2;
    expect(conversation.pendingAttachments.value).toEqual([]);
    conversation.addAttachment(attachment);
    phase.value = 'SOLUTION_DESIGN';
    expect(conversation.pendingAttachments.value).toEqual([]);
    conversation.addAttachment(attachment);
    workbenchId.value = 'wb-2';
    expect(conversation.pendingAttachments.value).toEqual([]);
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
