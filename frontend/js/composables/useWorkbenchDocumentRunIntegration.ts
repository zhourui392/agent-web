/**
 * Workbench Run FILE_CHANGED 到 Document Pane stale/deleted 状态的作用域安全接线。
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
} from 'vue';
import {
  useWorkbenchRunStream,
  type UseWorkbenchRunStream,
  type UseWorkbenchRunStreamOptions,
} from './useWorkbenchRunStream.js';
import type {
  WorkbenchDocumentEventScope,
  WorkbenchDocumentFileChangedEvent,
} from './useWorkbenchDocumentPane.js';
import type {
  WorkbenchRunMarkerIdentity,
  WorkbenchRunState,
} from '../lib/workbench-run-state.js';
import type { WorkbenchPhase } from '../lib/workbench-state.js';

export type WorkbenchRunStreamFactory = (
  options: UseWorkbenchRunStreamOptions,
) => UseWorkbenchRunStream;

export interface UseWorkbenchDocumentRunIntegrationOptions {
  userId: Readonly<Ref<string>>;
  workbenchId: Readonly<Ref<string | null>>;
  phase: Readonly<Ref<WorkbenchPhase>>;
  conversationGeneration: Readonly<Ref<number>>;
  activeRunId: Readonly<Ref<string | null>>;
  archived: Readonly<Ref<boolean>>;
  documentEventScope: Readonly<Ref<WorkbenchDocumentEventScope | null>>;
  receiveDocumentFileChanged(
    scope: WorkbenchDocumentEventScope | null,
    event: WorkbenchDocumentFileChangedEvent,
  ): boolean;
  runStreamFactory?: WorkbenchRunStreamFactory;
}

interface DocumentRunBinding {
  scope: WorkbenchDocumentEventScope;
  workbenchId: string;
  phase: WorkbenchPhase;
  runId: string;
  lastProjectedEventId: number;
}

function validConversationGeneration(value: number): boolean {
  return Number.isSafeInteger(value) && value >= 0;
}

function scopeMatches(
  scope: WorkbenchDocumentEventScope,
  userId: string,
  workbenchId: string,
  phase: WorkbenchPhase,
): boolean {
  return scope.userId === userId
    && scope.workbenchId === workbenchId
    && scope.phase === phase;
}

function stateMatchesBinding(
  state: WorkbenchRunState,
  binding: DocumentRunBinding,
): boolean {
  return state.context.workbenchId === binding.workbenchId
    && state.context.phase === binding.phase
    && state.context.runId === binding.runId;
}

export function useWorkbenchDocumentRunIntegration(
  options: UseWorkbenchDocumentRunIntegrationOptions,
): void {
  const streamIdentity = shallowRef<WorkbenchRunMarkerIdentity | null>(null);
  const createRunStream = options.runStreamFactory || useWorkbenchRunStream;
  const stream = createRunStream({ identity: streamIdentity });
  let binding: DocumentRunBinding | null = null;

  function projectDocumentChanges(state: WorkbenchRunState | null): void {
    const activeBinding = binding;
    if (!state || !activeBinding || !stateMatchesBinding(state, activeBinding)) return;

    const unseenChanges = state.staleDocuments
      .filter(changed => changed.eventId > activeBinding.lastProjectedEventId)
      .slice()
      .sort((left, right) => left.eventId - right.eventId);
    for (const changed of unseenChanges) {
      activeBinding.lastProjectedEventId = changed.eventId;
      options.receiveDocumentFileChanged(activeBinding.scope, {
        repositoryKey: changed.repositoryKey,
        relativePath: changed.path,
        changeType: changed.changeType,
        contentVersion: changed.contentVersion,
      });
    }
  }

  function synchronizeSubscription(): void {
    stream.close();
    binding = null;

    const userId = options.userId.value;
    const workbenchId = options.workbenchId.value;
    const phase = options.phase.value;
    const conversationGeneration = options.conversationGeneration.value;
    const runId = options.activeRunId.value;
    if (options.archived.value
      || !userId
      || !workbenchId
      || !runId
      || !validConversationGeneration(conversationGeneration)) {
      streamIdentity.value = null;
      return;
    }

    streamIdentity.value = {
      userId,
      workbenchId,
      phase,
      conversationGeneration,
    };
    const capturedScope = options.documentEventScope.value;
    if (!capturedScope || !scopeMatches(capturedScope, userId, workbenchId, phase)) {
      streamIdentity.value = null;
      return;
    }

    binding = {
      scope: capturedScope,
      workbenchId,
      phase,
      runId,
      lastProjectedEventId: 0,
    };
    if (!stream.resume(runId) && !stream.attach(runId)) {
      binding = null;
    }
  }

  watch(stream.state, projectDocumentChanges, { flush: 'sync' });
  watch(
    () => [
      options.userId.value,
      options.workbenchId.value,
      options.phase.value,
      options.conversationGeneration.value,
      options.activeRunId.value,
      options.archived.value,
      options.documentEventScope.value,
    ] as const,
    synchronizeSubscription,
    { immediate: true, flush: 'sync' },
  );

  if (getCurrentScope()) {
    onScopeDispose(() => {
      stream.close();
      binding = null;
      streamIdentity.value = null;
    });
  }
}
