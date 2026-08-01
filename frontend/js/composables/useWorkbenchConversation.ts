/**
 * Workbench Phase Run 提交、可恢复 SSE 和停止编排。
 *
 * @author alex
 * @since 2026-08-01
 */
import { computed, ref, watch, type Ref } from 'vue';
import {
  WorkbenchRunApiError,
  createWorkbenchRunApiClient,
  type WorkbenchPhaseConversation,
  type WorkbenchRunApiClient,
  type WorkbenchRunSubmission,
} from '../api/workbench-run.js';
import {
  useWorkbenchRunStream,
  type UseWorkbenchRunStream,
} from './useWorkbenchRunStream.js';
import type {
  WorkbenchRunMarkerIdentity,
  WorkbenchRunMode,
} from '../lib/workbench-run-state.js';
import type { WorkbenchPhase } from '../lib/workbench-state.js';

export interface UseWorkbenchConversationOptions {
  ownerId: Ref<string>;
  workbenchId: Ref<string | null>;
  phase: Ref<WorkbenchPhase>;
  conversationGeneration: Ref<number>;
  currentConversationId?: Ref<string | null>;
  activeRunId: Ref<string | null>;
  expectedVersion: Ref<number | null>;
  archived?: Ref<boolean>;
  handoffRequired: Ref<boolean>;
  handoffSourceVersion: Ref<number | null>;
  reviewConfirmationId: Ref<string | null>;
  apiClient?: WorkbenchRunApiClient;
  stream?: UseWorkbenchRunStream;
  onSubmitted?: (submission: WorkbenchRunSubmission, mode: WorkbenchRunMode) => void;
  onConversationEnsured?: (conversation: WorkbenchPhaseConversation) => void;
  onTerminal?: (runId: string) => void;
}

export function useWorkbenchConversation(options: UseWorkbenchConversationOptions) {
  const apiClient = options.apiClient ?? createWorkbenchRunApiClient();
  const identity = computed<WorkbenchRunMarkerIdentity | null>(() => {
    const userId = options.ownerId.value?.trim();
    const workbenchId = options.workbenchId.value?.trim();
    const generation = options.conversationGeneration.value;
    return userId && workbenchId && Number.isSafeInteger(generation) && generation >= 0
      ? { userId, workbenchId, phase: options.phase.value, conversationGeneration: generation }
      : null;
  });
  const stream = options.stream ?? useWorkbenchRunStream({
    identity,
    eventUrl: (workbenchId, runId) => apiClient.eventsUrl(workbenchId, runId),
  });
  const composerText = ref('');
  const runMode = ref<WorkbenchRunMode>(defaultRunMode(options.phase.value));
  const submitting = ref(false);
  const stopping = ref(false);
  const conversationError = ref<string | null>(null);
  const conversationNotice = ref<string | null>(null);
  const localRunId = ref<string | null>(null);
  let failedSubmission: { fingerprint: string; idempotencyKey: string } | null = null;
  let lastTerminalKey = '';

  const conversationReadOnly = computed(() => options.archived?.value ?? false);
  const identityReady = computed(() => identity.value != null);
  const modifyAllowed = computed(() =>
    options.phase.value === 'IMPLEMENT_TEST' || options.phase.value === 'REVIEW_REFACTOR');
  const handoffReady = computed(() =>
    !options.handoffRequired.value || options.handoffSourceVersion.value != null);
  const currentRunId = computed(() =>
    options.activeRunId.value || localRunId.value || stream.state.value?.context.runId || null);
  const runActive = computed(() => Boolean(
    options.activeRunId.value ||
    localRunId.value && !stream.state.value?.terminal ||
    ['PENDING', 'RUNNING', 'CANCEL_REQUESTED'].includes(stream.state.value?.status || ''),
  ));
  const conversationCanSubmit = computed(() =>
    !conversationReadOnly.value &&
    identityReady.value &&
    !submitting.value &&
    !runActive.value &&
    handoffReady.value &&
    Boolean(composerText.value.trim()),
  );

  function updateComposerText(value: string): void {
    if (typeof value !== 'string' || conversationReadOnly.value) return;
    composerText.value = value;
    conversationError.value = null;
    conversationNotice.value = null;
    if (failedSubmission?.fingerprint !== submissionFingerprintOrNull()) failedSubmission = null;
  }

  function updateRunMode(mode: WorkbenchRunMode): void {
    if (mode !== 'DISCUSS_READ_ONLY' && mode !== 'MODIFY_WORKSPACE') return;
    if (mode === 'MODIFY_WORKSPACE' && !modifyAllowed.value) return;
    runMode.value = mode;
    conversationError.value = null;
    conversationNotice.value = null;
    if (failedSubmission?.fingerprint !== submissionFingerprintOrNull()) failedSubmission = null;
  }

  async function submitConversation(): Promise<void> {
    const workbenchId = options.workbenchId.value?.trim();
    const expectedVersion = options.expectedVersion.value;
    const message = composerText.value;
    if (!workbenchId || conversationReadOnly.value || submitting.value) return;
    conversationError.value = null;
    conversationNotice.value = null;
    if (!identityReady.value) {
      conversationError.value = '当前用户身份不可用，无法建立隔离的 Run 恢复流，请重新登录后重试。';
      return;
    }
    if (!message.trim()) {
      conversationError.value = '请输入本阶段问题或任务。';
      return;
    }
    if (runActive.value) {
      conversationError.value = '当前 Workbench 已有写任务，请等待终态或先停止当前 Run。';
      return;
    }
    if (!handoffReady.value) {
      conversationError.value = '请先预览并接受上游阶段交接版本。';
      return;
    }
    if (!Number.isSafeInteger(expectedVersion) || expectedVersion == null || expectedVersion < 0) {
      conversationError.value = 'Workbench 版本不可用，请刷新后重试。';
      return;
    }
    if (runMode.value === 'MODIFY_WORKSPACE' && !modifyAllowed.value) {
      conversationError.value = '当前阶段只允许只读讨论。';
      return;
    }
    const reviewModify = options.phase.value === 'REVIEW_REFACTOR' && runMode.value === 'MODIFY_WORKSPACE';
    const confirmationId = options.reviewConfirmationId.value;
    if (reviewModify && !confirmationId) {
      conversationError.value = '请先保存并精确确认当前 Review Opinion，再启动重构写入。';
      return;
    }
    submitting.value = true;
    try {
      let submissionVersion = expectedVersion;
      if (options.currentConversationId
        && !options.currentConversationId.value?.trim()) {
        const ensured = await apiClient.ensureConversation(
          workbenchId, options.phase.value, expectedVersion,
        );
        submissionVersion = ensured.workbenchVersion;
        options.onConversationEnsured?.(ensured);
      }
      const fingerprint = submissionFingerprint(
        workbenchId,
        submissionVersion,
        message,
        runMode.value,
        options.handoffSourceVersion.value,
        reviewModify ? confirmationId : null,
      );
      const idempotencyKey = failedSubmission?.fingerprint === fingerprint
        ? failedSubmission.idempotencyKey
        : newIdempotencyKey();
      failedSubmission = { fingerprint, idempotencyKey };
      const request = {
        message,
        runMode: runMode.value,
        ...(options.handoffSourceVersion.value == null
          ? {} : { handoffSourceVersion: options.handoffSourceVersion.value }),
        ...(reviewModify ? { reviewConfirmationId: confirmationId } : {}),
      };
      const submitted = await apiClient.submitRun({
        workbenchId,
        phase: options.phase.value,
        expectedVersion: submissionVersion,
        idempotencyKey,
        request,
      });
      localRunId.value = submitted.runId;
      stream.attach(submitted.runId, 0);
      failedSubmission = null;
      composerText.value = '';
      conversationNotice.value = submitted.replayed
        ? '已恢复同一幂等请求创建的 Run。'
        : 'Run 已提交，正在等待 Runtime 输出。';
      options.onSubmitted?.(submitted, runMode.value);
    } catch (error) {
      conversationError.value = conversationErrorMessage(error);
    } finally {
      submitting.value = false;
    }
  }

  async function stopConversation(): Promise<void> {
    const workbenchId = options.workbenchId.value?.trim();
    const runId = currentRunId.value;
    if (!workbenchId || !runId || conversationReadOnly.value || stopping.value) return;
    stopping.value = true;
    conversationError.value = null;
    conversationNotice.value = null;
    try {
      await apiClient.stopRun(workbenchId, runId);
      conversationNotice.value = '停止请求已记录，页面会持续等待并展示明确终态。';
    } catch (error) {
      conversationError.value = conversationErrorMessage(error);
    } finally {
      stopping.value = false;
    }
  }

  function submissionFingerprintOrNull(): string | null {
    const workbenchId = options.workbenchId.value?.trim();
    const version = options.expectedVersion.value;
    if (!workbenchId || version == null) return null;
    return submissionFingerprint(
      workbenchId,
      version,
      composerText.value,
      runMode.value,
      options.handoffSourceVersion.value,
      options.reviewConfirmationId.value,
    );
  }

  watch(
    () => [
      identity.value?.userId ?? '',
      identity.value?.workbenchId ?? '',
      options.phase.value,
      options.conversationGeneration.value,
    ].join('\u0000'),
    () => {
      composerText.value = '';
      runMode.value = defaultRunMode(options.phase.value);
      localRunId.value = null;
      conversationError.value = null;
      conversationNotice.value = null;
      failedSubmission = null;
      lastTerminalKey = '';
    },
    { flush: 'sync' },
  );

  watch(
    () => `${identity.value?.workbenchId ?? ''}\u0000${options.activeRunId.value ?? ''}`,
    () => {
      if (!identity.value) return;
      const activeRunId = options.activeRunId.value;
      if (activeRunId) {
        localRunId.value = activeRunId;
        if (stream.state.value?.context.runId === activeRunId) return;
        if (!stream.resume(activeRunId)) stream.attach(activeRunId, 0);
      } else if (!localRunId.value) {
        stream.resume();
      }
    },
    { immediate: true, flush: 'sync' },
  );

  watch(
    () => stream.error.value?.code ?? '',
    code => {
      if (code === 'CURSOR_EXPIRED') {
        conversationError.value = 'Run 事件游标已过期；请刷新 Run 状态，系统不会自动重放写操作。';
      } else if (code === 'UNAUTHORIZED') {
        conversationError.value = '当前登录态无权恢复该 Run。';
      } else if (code) {
        conversationError.value = 'Run 实时连接失败，请稍后刷新恢复。';
      }
    },
    { flush: 'sync' },
  );

  watch(
    () => stream.state.value?.terminal
      ? `${stream.state.value.context.runId}:${stream.state.value.terminal.eventId}`
      : '',
    key => {
      if (!key || key === lastTerminalKey || !stream.state.value?.terminal) return;
      lastTerminalKey = key;
      localRunId.value = null;
      options.onTerminal?.(stream.state.value.context.runId);
    },
    { flush: 'sync' },
  );

  return {
    composerText,
    runMode,
    runState: stream.state,
    streamError: stream.error,
    connectionStatus: stream.connectionStatus,
    submitting,
    stopping,
    conversationError,
    conversationNotice,
    conversationReadOnly,
    identityReady,
    modifyAllowed,
    handoffReady,
    currentRunId,
    runActive,
    conversationCanSubmit,
    updateComposerText,
    updateRunMode,
    submitConversation,
    stopConversation,
  };
}

function defaultRunMode(phase: WorkbenchPhase): WorkbenchRunMode {
  return phase === 'IMPLEMENT_TEST' ? 'MODIFY_WORKSPACE' : 'DISCUSS_READ_ONLY';
}

function newIdempotencyKey(): string {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return `workbench-run-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function submissionFingerprint(
  workbenchId: string,
  expectedVersion: number,
  message: string,
  runMode: WorkbenchRunMode,
  handoffSourceVersion: number | null,
  reviewConfirmationId: string | null,
): string {
  return JSON.stringify([
    workbenchId,
    expectedVersion,
    message,
    runMode,
    handoffSourceVersion,
    reviewConfirmationId,
  ]);
}

function conversationErrorMessage(error: unknown): string {
  if (!(error instanceof WorkbenchRunApiError)) {
    return 'Workbench Run 请求失败，请稍后重试。';
  }
  switch (error.code) {
    case 'WORKBENCH_RUN_NOT_FOUND':
    case 'WORKBENCH_NOT_FOUND':
      return 'Run 不存在或无权访问。';
    case 'ACTIVE_RUN_CONFLICT':
    case 'ACTIVE_WRITE_RUN_CONFLICT':
      return '当前 Workbench 已有活动写 Run，不能启动第二个写任务。';
    case 'WORKBENCH_VERSION_CONFLICT':
    case 'WORKBENCH_RUN_CONFLICT':
      return 'Workbench 状态已变化，请刷新后重试。';
    case 'WORKBENCH_RUN_CURSOR_EXPIRED':
    case 'CURSOR_EXPIRED':
      return 'Run 事件游标已过期，请刷新状态；系统不会自动重放写操作。';
    case 'WORKBENCH_RUN_INVALID':
    case 'VALIDATION_ERROR':
    case 'INVALID_REQUEST':
      return 'Run 请求不符合当前阶段或能力约束。';
    case 'RUNTIME_UNAVAILABLE':
    case 'WORKBENCH_RUN_UNAVAILABLE':
      return 'Runtime 当前不可用，请检查阶段能力或稍后重试。';
    default:
      return 'Workbench Run 请求失败，请稍后重试。';
  }
}
