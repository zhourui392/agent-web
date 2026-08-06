/**
 * Workbench Stage Run 提交、可恢复 SSE 和停止编排。
 *
 * @author alex
 * @since 2026-08-01
 */
import { computed, ref, watch, type Ref } from 'vue';
import {
  WorkbenchRunApiError,
  createWorkbenchRunApiClient,
  normalizeWorkbenchRunAttachments,
  type WorkbenchStageConversation,
  type WorkbenchStageConversationMessage,
  type WorkbenchStageConversationRestart,
  type WorkbenchRepositoryDocumentAttachment,
  type WorkbenchRunAttachment,
  type WorkbenchRunApiClient,
  type WorkbenchRunSubmission,
  type WorkbenchUploadedConversationAttachment,
} from '../api/workbench-run.js';
import {
  useWorkbenchRunStream,
  type UseWorkbenchRunStream,
} from './useWorkbenchRunStream.js';
import type {
  WorkbenchRunMarkerIdentity,
  WorkbenchRunMode,
} from '../lib/workbench-run-state.js';
import type { WorkbenchStageStatus } from '../lib/workbench-state.js';

export interface UseWorkbenchConversationOptions {
  ownerId: Ref<string>;
  workbenchId: Ref<string | null>;
  stageInstanceIdentifier: Ref<string | null>;
  conversationGeneration: Ref<number>;
  currentConversationId?: Ref<string | null>;
  activeRunId: Ref<string | null>;
  activeWriteRunId?: Ref<string | null>;
  expectedVersion: Ref<number | null>;
  allowedRunModes: Ref<ReadonlyArray<WorkbenchRunMode>>;
  stageStatus?: Ref<WorkbenchStageStatus>;
  archived?: Ref<boolean>;
  apiClient?: WorkbenchRunApiClient;
  stream?: UseWorkbenchRunStream;
  onSubmitted?: (
    submission: WorkbenchRunSubmission,
    mode: WorkbenchRunMode,
    attachments: ReadonlyArray<WorkbenchRunAttachment>,
  ) => void;
  onConversationEnsured?: (conversation: WorkbenchStageConversation) => void;
  onConversationRestarted?: (conversation: WorkbenchStageConversationRestart) => void;
  onTerminal?: (runId: string) => void;
}

export interface PendingUploadedWorkbenchAttachment
  extends WorkbenchUploadedConversationAttachment {
  displayName: string;
  mediaType: string;
  size: number;
  previewUrl: string | null;
}

export type PendingWorkbenchAttachment =
  | WorkbenchRepositoryDocumentAttachment
  | PendingUploadedWorkbenchAttachment;

interface WorkbenchConversationIdentity {
  userId: string;
  workbenchId: string;
  stageInstanceIdentifier: string;
  conversationGeneration: number;
}

export function useWorkbenchConversation(options: UseWorkbenchConversationOptions) {
  const apiClient = options.apiClient ?? createWorkbenchRunApiClient();
  const conversationIdentity = computed<WorkbenchConversationIdentity | null>(() => {
    const userId = options.ownerId.value?.trim();
    const workbenchId = options.workbenchId.value?.trim();
    const generation = options.conversationGeneration.value;
    const stageInstanceIdentifier =
      options.stageInstanceIdentifier.value?.trim();
    return userId && workbenchId && stageInstanceIdentifier
      && Number.isSafeInteger(generation) && generation >= 0
      ? {
          userId,
          workbenchId,
          stageInstanceIdentifier,
          conversationGeneration: generation,
        }
      : null;
  });
  const runIdentity = computed<WorkbenchRunMarkerIdentity | null>(() => {
    const current = conversationIdentity.value;
    return current
      ? {
          userId: current.userId,
          workbenchId: current.workbenchId,
          stageInstanceIdentifier: current.stageInstanceIdentifier,
          conversationGeneration: current.conversationGeneration,
        }
      : null;
  });
  const stream = options.stream ?? useWorkbenchRunStream({
    identity: runIdentity,
    eventUrl: (workbenchId, runId) => apiClient.eventsUrl(workbenchId, runId),
  });
  const composerText = ref('');
  const submitting = ref(false);
  const stopping = ref(false);
  const messagesLoading = ref(false);
  const olderMessagesLoading = ref(false);
  const restarting = ref(false);
  const conversationMessages = ref<WorkbenchStageConversationMessage[]>([]);
  const olderMessagesCursor = ref<number | null>(null);
  const pendingAttachments = ref<PendingWorkbenchAttachment[]>([]);
  const conversationError = ref<string | null>(null);
  const conversationNotice = ref<string | null>(null);
  const localRunId = ref<string | null>(null);
  const allowedRunModes = computed(() => normalizeAllowedRunModes(
    options.allowedRunModes.value,
  ));
  const selectedRunMode = ref<WorkbenchRunMode | null>(
    initialRunMode(allowedRunModes.value),
  );
  let failedSubmission: { fingerprint: string; idempotencyKey: string } | null = null;
  let failedRestart: { fingerprint: string; idempotencyKey: string } | null = null;
  let submitRequestToken = 0;
  let restartRequestToken = 0;
  let messageRequestToken = 0;
  let lastTerminalKey = '';

  const conversationReadOnly = computed(() => options.archived?.value ?? false);
  const identityReady = computed(() => conversationIdentity.value != null);
  const currentRunId = computed(() =>
    options.activeRunId.value || localRunId.value || stream.state.value?.context.runId || null);
  const runActive = computed(() => Boolean(
    options.activeRunId.value ||
    localRunId.value && !stream.state.value?.terminal ||
    ['PENDING', 'RUNNING', 'CANCEL_REQUESTED'].includes(stream.state.value?.status || ''),
  ));
  const conversationCanSubmit = computed(() =>
    !conversationReadOnly.value &&
    runIdentity.value != null &&
    selectedRunMode.value != null &&
    !submitting.value &&
    !runActive.value &&
    Boolean(composerText.value.trim()),
  );
  const canRestartConversation = computed(() => {
    const expectedVersion = options.expectedVersion.value;
    return !conversationReadOnly.value
      && identityReady.value
      && options.stageStatus?.value === 'IN_PROGRESS'
      && !runActive.value
      && !submitting.value
      && !stopping.value
      && !restarting.value
      && Boolean(options.currentConversationId?.value?.trim())
      && expectedVersion != null
      && Number.isSafeInteger(expectedVersion)
      && expectedVersion >= 0;
  });
  const hasOlderConversationMessages = computed(() => olderMessagesCursor.value != null);

  async function refreshConversationMessages(): Promise<void> {
    const currentIdentity = conversationIdentity.value;
    const workbenchId = currentIdentity?.workbenchId;
    const stageInstanceIdentifier = currentIdentity?.stageInstanceIdentifier;
    const generation = currentIdentity?.conversationGeneration;
    const sessionId = options.currentConversationId?.value?.trim() || null;
    const requestToken = ++messageRequestToken;
    if (!currentIdentity || !workbenchId
      || !stageInstanceIdentifier || generation == null) {
      conversationMessages.value = [];
      olderMessagesCursor.value = null;
      olderMessagesLoading.value = false;
      messagesLoading.value = false;
      return;
    }
    messagesLoading.value = true;
    try {
      const response = await apiClient.getStageConversationMessages(
        workbenchId, stageInstanceIdentifier,
      );
      if (requestToken !== messageRequestToken) return;
      if (response.generation !== generation
        || options.currentConversationId && response.sessionId !== sessionId) {
        return;
      }
      conversationMessages.value = response.messages;
      olderMessagesCursor.value = response.nextCursor;
    } catch (error) {
      if (requestToken === messageRequestToken) {
        conversationError.value = conversationErrorMessage(error);
      }
    } finally {
      if (requestToken === messageRequestToken) messagesLoading.value = false;
    }
  }

  async function loadOlderConversationMessages(): Promise<void> {
    const currentIdentity = conversationIdentity.value;
    const workbenchId = currentIdentity?.workbenchId;
    const stageInstanceIdentifier = currentIdentity?.stageInstanceIdentifier;
    const generation = currentIdentity?.conversationGeneration;
    const sessionId = options.currentConversationId?.value?.trim() || null;
    const cursor = olderMessagesCursor.value;
    const requestToken = messageRequestToken;
    if (!currentIdentity || !workbenchId
      || !stageInstanceIdentifier || generation == null
      || !sessionId || cursor == null || olderMessagesLoading.value) return;
    olderMessagesLoading.value = true;
    conversationError.value = null;
    try {
      const response = await apiClient.getStageConversationMessages(
        workbenchId, stageInstanceIdentifier, cursor,
      );
      if (requestToken !== messageRequestToken) return;
      if (response.generation !== generation || response.sessionId !== sessionId
        || response.messages.some(message => message.messageId >= cursor)) {
        return;
      }
      const merged = new Map<number, WorkbenchStageConversationMessage>();
      for (const message of response.messages) merged.set(message.messageId, message);
      for (const message of conversationMessages.value) merged.set(message.messageId, message);
      conversationMessages.value = Array.from(merged.values())
        .sort((left, right) => left.messageId - right.messageId);
      olderMessagesCursor.value = response.nextCursor;
    } catch (error) {
      if (requestToken === messageRequestToken) {
        conversationError.value = conversationErrorMessage(error);
      }
    } finally {
      if (requestToken === messageRequestToken) olderMessagesLoading.value = false;
    }
  }

  function updateComposerText(value: string): void {
    if (typeof value !== 'string' || conversationReadOnly.value) return;
    composerText.value = value;
    conversationError.value = null;
    conversationNotice.value = null;
    if (failedSubmission?.fingerprint !== submissionFingerprintOrNull()) failedSubmission = null;
  }

  function selectRunMode(mode: WorkbenchRunMode): boolean {
    if (!allowedRunModes.value.includes(mode)) return false;
    selectedRunMode.value = mode;
    conversationError.value = null;
    conversationNotice.value = null;
    if (failedSubmission?.fingerprint !== submissionFingerprintOrNull()) {
      failedSubmission = null;
    }
    return true;
  }

  function addAttachment(attachment: WorkbenchRunAttachment): boolean {
    if (conversationReadOnly.value) return false;
    let pending: PendingWorkbenchAttachment;
    try {
      const normalized = normalizeWorkbenchRunAttachments([attachment])[0];
      if (!normalized) return false;
      pending = normalized.type === 'UPLOADED_CONVERSATION'
        ? pendingUploadedAttachment(attachment, normalized)
        : normalized;
      normalizeWorkbenchRunAttachments([...pendingAttachments.value, pending]);
    } catch {
      return false;
    }
    pendingAttachments.value = [...pendingAttachments.value, pending];
    conversationError.value = null;
    conversationNotice.value = null;
    if (failedSubmission?.fingerprint !== submissionFingerprintOrNull()) failedSubmission = null;
    return true;
  }

  function removeAttachment(repositoryKey: string, relativePath: string): void {
    if (conversationReadOnly.value) return;
    const remaining = pendingAttachments.value.filter(attachment => (
      attachment.type === 'UPLOADED_CONVERSATION'
      || attachment.repositoryKey !== repositoryKey
      || attachment.relativePath !== relativePath
    ));
    if (remaining.length === pendingAttachments.value.length) return;
    pendingAttachments.value = remaining;
    conversationError.value = null;
    conversationNotice.value = null;
    if (failedSubmission?.fingerprint !== submissionFingerprintOrNull()) failedSubmission = null;
  }

  function removeUploadedAttachment(attachmentId: string): void {
    if (conversationReadOnly.value || typeof attachmentId !== 'string') return;
    const remaining = pendingAttachments.value.filter(attachment => (
      attachment.type !== 'UPLOADED_CONVERSATION'
      || attachment.attachmentId !== attachmentId
    ));
    if (remaining.length === pendingAttachments.value.length) return;
    pendingAttachments.value = remaining;
    conversationError.value = null;
    conversationNotice.value = null;
    if (failedSubmission?.fingerprint !== submissionFingerprintOrNull()) failedSubmission = null;
  }

  function isAttachmentPending(repositoryKey: unknown, relativePath: unknown): boolean {
    return typeof repositoryKey === 'string' && typeof relativePath === 'string'
      && pendingAttachments.value.some(attachment => (
        attachment.type !== 'UPLOADED_CONVERSATION'
        &&
        attachment.repositoryKey === repositoryKey && attachment.relativePath === relativePath
      ));
  }

  async function submitConversation(): Promise<void> {
    const workbenchId = options.workbenchId.value?.trim();
    const expectedVersion = options.expectedVersion.value;
    const message = composerText.value;
    const submissionIdentity = runIdentity.value;
    if (!workbenchId || conversationReadOnly.value || submitting.value) return;
    conversationError.value = null;
    conversationNotice.value = null;
    if (!submissionIdentity) {
      conversationError.value = '当前用户身份不可用，无法建立隔离的 Run 恢复流，请重新登录后重试。';
      return;
    }
    if (!message.trim()) {
      conversationError.value = '请输入本阶段问题或任务。';
      return;
    }
    if (runActive.value) {
      conversationError.value = '当前阶段已有活动 Run，请等待终态或先停止当前 Run。';
      return;
    }
    const submissionRunMode = selectedRunMode.value;
    if (!submissionRunMode
      || !allowedRunModes.value.includes(submissionRunMode)) {
      conversationError.value = '请选择当前阶段允许的运行模式。';
      return;
    }
    if (!Number.isSafeInteger(expectedVersion) || expectedVersion == null || expectedVersion < 0) {
      conversationError.value = 'Workbench 版本不可用，请刷新后重试。';
      return;
    }
    const submissionStageInstanceIdentifier =
      submissionIdentity.stageInstanceIdentifier;
    const requestToken = ++submitRequestToken;
    submitting.value = true;
    try {
      const attachments = normalizeWorkbenchRunAttachments(pendingAttachments.value);
      let submissionVersion = expectedVersion;
      if (options.currentConversationId
        && !options.currentConversationId.value?.trim()) {
        const ensured = await apiClient.ensureStageConversation(
          workbenchId, submissionStageInstanceIdentifier, expectedVersion,
        );
        if (!isCurrentSubmission(requestToken, submissionIdentity)) return;
        submissionVersion = ensured.workbenchVersion;
        options.onConversationEnsured?.(ensured);
      }
      const fingerprint = submissionFingerprint(
        workbenchId,
        submissionVersion,
        message,
        submissionRunMode,
        attachments,
      );
      const idempotencyKey = failedSubmission?.fingerprint === fingerprint
        ? failedSubmission.idempotencyKey
        : newIdempotencyKey();
      failedSubmission = { fingerprint, idempotencyKey };
      const request = {
        message,
        runMode: submissionRunMode,
        ...(attachments.length === 0 ? {} : { attachments }),
      };
      const submitted = await apiClient.submitRun({
        workbenchId,
        stageInstanceIdentifier: submissionStageInstanceIdentifier,
        expectedVersion: submissionVersion,
        idempotencyKey,
        request,
      });
      if (!isCurrentSubmission(requestToken, submissionIdentity)) return;
      localRunId.value = submitted.runId;
      stream.attach(submitted.runId, 0);
      failedSubmission = null;
      composerText.value = '';
      pendingAttachments.value = [];
      conversationNotice.value = submitted.replayed
        ? '已恢复同一幂等请求创建的 Run。'
        : 'Run 已提交，正在等待 Runtime 输出。';
      options.onSubmitted?.(submitted, submissionRunMode, attachments);
      await refreshConversationMessages();
    } catch (error) {
      if (isCurrentSubmission(requestToken, submissionIdentity)) {
        conversationError.value = conversationErrorMessage(error);
      }
    } finally {
      if (requestToken === submitRequestToken) submitting.value = false;
    }
  }

  function isCurrentSubmission(
    requestToken: number,
    expectedIdentity: WorkbenchRunMarkerIdentity,
  ): boolean {
    return requestToken === submitRequestToken
      && sameRunIdentity(runIdentity.value, expectedIdentity);
  }

  async function restartConversation(): Promise<void> {
    if (!canRestartConversation.value) return;
    const workbenchId = options.workbenchId.value?.trim();
    const expectedVersion = options.expectedVersion.value;
    const currentSessionId = options.currentConversationId?.value?.trim();
    if (!workbenchId || expectedVersion == null || !currentSessionId) return;
    const fingerprint = restartFingerprint(
      conversationIdentity.value,
      currentSessionId,
      expectedVersion,
    );
    const idempotencyKey = failedRestart?.fingerprint === fingerprint
      ? failedRestart.idempotencyKey
      : newIdempotencyKey();
    failedRestart = { fingerprint, idempotencyKey };
    const requestToken = ++restartRequestToken;
    restarting.value = true;
    conversationError.value = null;
    conversationNotice.value = null;
    try {
      const stageInstanceIdentifier =
        conversationIdentity.value?.stageInstanceIdentifier;
      if (!stageInstanceIdentifier) return;
      const restarted = await apiClient.restartStageConversation(
        workbenchId,
        stageInstanceIdentifier,
        expectedVersion,
        idempotencyKey,
      );
      if (!isCurrentRestart(requestToken, fingerprint)) return;
      stream.close();
      stream.state.value = null;
      localRunId.value = null;
      composerText.value = '';
      pendingAttachments.value = [];
      failedSubmission = null;
      failedRestart = null;
      lastTerminalKey = '';
      messageRequestToken += 1;
      conversationMessages.value = [];
      olderMessagesCursor.value = null;
      messagesLoading.value = false;
      olderMessagesLoading.value = false;
      options.onConversationRestarted?.(restarted);
      conversationNotice.value = restarted.replayed
        ? '已恢复同一幂等请求创建的新会话；旧历史只读保留，新会话未复制任何消息。'
        : '会话已重启；旧历史只读保留，新会话未复制任何消息。';
    } catch (error) {
      if (isCurrentRestart(requestToken, fingerprint)) {
        conversationError.value = conversationErrorMessage(error);
      }
    } finally {
      if (requestToken === restartRequestToken) restarting.value = false;
    }
  }

  function isCurrentRestart(
    requestToken: number,
    fingerprint: string,
  ): boolean {
    const currentSessionId = options.currentConversationId?.value?.trim();
    const currentVersion = options.expectedVersion.value;
    return requestToken === restartRequestToken
      && Boolean(currentSessionId)
      && currentVersion != null
      && restartFingerprint(
        conversationIdentity.value,
        currentSessionId as string,
        currentVersion,
      ) === fingerprint;
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
    const runMode = selectedRunMode.value;
    if (!workbenchId || version == null || !runMode) return null;
    return submissionFingerprint(
      workbenchId,
      version,
      composerText.value,
      runMode,
      pendingAttachments.value,
    );
  }

  watch(
    () => [
      options.stageInstanceIdentifier.value ?? '',
      allowedRunModes.value.join(','),
    ].join('\u0000'),
    () => {
      selectedRunMode.value = initialRunMode(allowedRunModes.value);
      failedSubmission = null;
    },
    { flush: 'sync' },
  );

  watch(
    () => conversationIdentityFingerprint(conversationIdentity.value),
    () => {
      composerText.value = '';
      pendingAttachments.value = [];
      localRunId.value = null;
      conversationError.value = null;
      conversationNotice.value = null;
      submitRequestToken += 1;
      restartRequestToken += 1;
      submitting.value = false;
      restarting.value = false;
      messageRequestToken += 1;
      conversationMessages.value = [];
      olderMessagesCursor.value = null;
      messagesLoading.value = false;
      olderMessagesLoading.value = false;
      failedSubmission = null;
      failedRestart = null;
      lastTerminalKey = '';
    },
    { flush: 'sync' },
  );

  watch(
    () => [
      conversationIdentityFingerprint(conversationIdentity.value),
      options.currentConversationId?.value ?? '',
    ].join('\u0000'),
    () => {
      void refreshConversationMessages();
    },
    { immediate: true, flush: 'post' },
  );

  watch(
    () => `${runIdentity.value?.workbenchId ?? ''}\u0000${options.activeRunId.value ?? ''}`,
    () => {
      if (!runIdentity.value) return;
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
      conversationNotice.value = null;
      void refreshConversationMessages();
      options.onTerminal?.(stream.state.value.context.runId);
    },
    { flush: 'sync' },
  );

  return {
    composerText,
    runState: stream.state,
    streamError: stream.error,
    connectionStatus: stream.connectionStatus,
    submitting,
    stopping,
    messagesLoading,
    olderMessagesLoading,
    restarting,
    conversationMessages,
    hasOlderConversationMessages,
    pendingAttachments,
    conversationError,
    conversationNotice,
    allowedRunModes,
    selectedRunMode,
    conversationReadOnly,
    identityReady,
    currentRunId,
    runActive,
    conversationCanSubmit,
    canRestartConversation,
    updateComposerText,
    selectRunMode,
    addAttachment,
    removeAttachment,
    removeUploadedAttachment,
    isAttachmentPending,
    submitConversation,
    stopConversation,
    refreshConversationMessages,
    loadOlderConversationMessages,
    restartConversation,
  };
}

function normalizeAllowedRunModes(
  candidates: ReadonlyArray<WorkbenchRunMode> | null | undefined,
): WorkbenchRunMode[] {
  if (!Array.isArray(candidates)) return [];
  const allowed = new Set(candidates);
  return (['DISCUSS_READ_ONLY', 'MODIFY_WORKSPACE'] as WorkbenchRunMode[])
    .filter(mode => allowed.has(mode));
}

function initialRunMode(
  allowedRunModes: ReadonlyArray<WorkbenchRunMode>,
): WorkbenchRunMode | null {
  if (allowedRunModes.length === 0) return null;
  // 多模式时默认选 MODIFY_WORKSPACE（开发/重构阶段需改仓库），避免无 UI 选择器时按钮被禁用
  return allowedRunModes.includes('MODIFY_WORKSPACE')
    ? 'MODIFY_WORKSPACE'
    : allowedRunModes[0];
}

function restartFingerprint(
  identity: WorkbenchConversationIdentity | null,
  currentSessionId: string,
  expectedVersion: number,
): string {
  return JSON.stringify([
    identity?.userId ?? '',
    identity?.workbenchId ?? '',
    identity?.stageInstanceIdentifier ?? '',
    identity?.conversationGeneration ?? -1,
    currentSessionId,
    expectedVersion,
  ]);
}

function conversationIdentityFingerprint(
  identity: WorkbenchConversationIdentity | null,
): string {
  if (!identity) return '';
  return JSON.stringify([
    identity.userId,
    identity.workbenchId,
    identity.stageInstanceIdentifier,
    identity.conversationGeneration,
  ]);
}

function sameRunIdentity(
  current: WorkbenchRunMarkerIdentity | null,
  expected: WorkbenchRunMarkerIdentity,
): boolean {
  return current != null
    && current.userId === expected.userId
    && current.workbenchId === expected.workbenchId
    && current.stageInstanceIdentifier === expected.stageInstanceIdentifier
    && current.conversationGeneration === expected.conversationGeneration;
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
  attachments: ReadonlyArray<WorkbenchRunAttachment>,
): string {
  const normalizedAttachments = normalizeWorkbenchRunAttachments(attachments);
  return JSON.stringify([
    workbenchId,
    expectedVersion,
    message,
    runMode,
    normalizedAttachments.map(attachment => attachment.type === 'UPLOADED_CONVERSATION'
      ? [attachment.type, attachment.attachmentId, attachment.contentHash]
      : ['REPOSITORY_DOCUMENT', attachment.repositoryKey,
          attachment.relativePath, attachment.contentHash]),
  ]);
}

function pendingUploadedAttachment(
  candidate: WorkbenchRunAttachment,
  normalized: WorkbenchUploadedConversationAttachment,
): PendingUploadedWorkbenchAttachment {
  const source = candidate as Partial<PendingUploadedWorkbenchAttachment>;
  const displayName = boundedPendingText(source.displayName, 255);
  const mediaType = boundedPendingText(source.mediaType, 128);
  const previewUrl = source.previewUrl == null
    ? null
    : boundedPendingText(source.previewUrl, 4_096);
  const size = source.size;
  if (!displayName || !mediaType || typeof size !== 'number'
    || !Number.isSafeInteger(size) || size < 1 || size > 10 * 1024 * 1024
    || source.previewUrl != null && !previewUrl) {
    throw new Error('uploaded workbench attachment metadata is invalid');
  }
  return {
    ...normalized,
    displayName,
    mediaType,
    size,
    previewUrl,
  };
}

function boundedPendingText(value: unknown, maximum: number): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.trim();
  return normalized && normalized.length <= maximum ? normalized : null;
}

function conversationErrorMessage(error: unknown): string {
  if (!(error instanceof WorkbenchRunApiError)) {
    if (error instanceof Error && error.message) {
      return error.message;
    }
    return 'Workbench Run 请求失败，请稍后重试。';
  }
  switch (error.code) {
    case 'AUTHENTICATION_REQUIRED':
    case 'UNAUTHORIZED':
      return '登录已过期，请重新登录。';
    case 'ACCESS_DENIED':
    case 'FORBIDDEN':
      return '无权执行此操作。';
    case 'WORKBENCH_RUN_NOT_FOUND':
    case 'WORKBENCH_NOT_FOUND':
      return 'Run 不存在或无权访问。';
    case 'ACTIVE_RUN_CONFLICT':
    case 'ACTIVE_WRITE_RUN_CONFLICT':
      return '当前 Workbench 已有活动写 Run，不能启动第二个写任务。';
    case 'WORKBENCH_VERSION_CONFLICT':
    case 'WORKBENCH_RUN_CONFLICT':
      return 'Workbench 状态已变化，请刷新后重试。';
    case 'WORKSPACE_TOPOLOGY_CHANGED':
    case 'WORKSPACE_REPOSITORY_NOT_FOUND':
    case 'WORKBENCH_REPOSITORY_SCOPE_INVALID':
      return '仓库目录已移动、消失或不再匹配冻结范围；请恢复原目录，或创建新的 Workbench。';
    case 'REPOSITORY_SCOPE_VIOLATION':
      return '当前请求超出本轮冻结的仓库范围，系统已停止执行。';
    case 'WORKBENCH_ATTACHMENT_INVALID':
      return '附件引用与当前 Workbench、阶段或会话不匹配，请重新选择。';
    case 'WORKBENCH_ATTACHMENT_TOO_LARGE':
      return '附件超过服务端大小限制，请移除后重试。';
    case 'WORKBENCH_ATTACHMENT_LIMIT_EXCEEDED':
      return '仓内文档和浏览器上传附件总数超过本轮限制。';
    case 'WORKBENCH_ATTACHMENT_UNAVAILABLE':
      return '上传附件已过期、已取消或已被其他 Run 使用，请重新选择。';
    case 'WORKBENCH_ATTACHMENT_STORAGE_UNAVAILABLE':
      return '附件存储服务不可用，请稍后重试。';
    case 'WORKBENCH_RUN_CURSOR_EXPIRED':
    case 'CURSOR_EXPIRED':
      return 'Run 事件游标已过期，请刷新状态；系统不会自动重放写操作。';
    case 'WORKBENCH_RUN_INVALID':
    case 'VALIDATION_ERROR':
    case 'INVALID_REQUEST':
      return 'Run 请求不符合当前阶段或能力约束。';
    case 'WORKBENCH_STAGE_MESSAGE_TOO_LARGE':
      return '消息内容过长，请缩短后重试。';
    case 'RUNTIME_UNAVAILABLE':
    case 'WORKBENCH_RUN_UNAVAILABLE':
    case 'SERVICE_UNAVAILABLE':
      return 'Runtime 当前不可用，请检查阶段能力或稍后重试。';
    case 'WORKBENCH_RUN_NETWORK_ERROR':
      return '网络连接失败，请检查网络后重试。';
    case 'WORKBENCH_RUN_UNEXPECTED_RESPONSE':
    case 'WORKBENCH_RUN_RESPONSE_INVALID':
      return '服务返回异常响应，请稍后重试。';
    case 'WORKBENCH_RUN_REQUEST_FAILED':
      return `Workbench Run 请求失败 (HTTP ${error.status})，请稍后重试。`;
    default:
      return 'Workbench Run 请求失败，请稍后重试。';
  }
}
