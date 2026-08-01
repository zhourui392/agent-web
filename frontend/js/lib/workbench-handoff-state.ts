/**
 * TD-07 Handoff 的纯前端草稿、冲突和 Reception 状态转换。
 *
 * @author alex
 * @since 2026-08-01
 */
import type {
  HandoffDocumentReference,
  HandoffEditableContent,
  HandoffReceptionView,
  HandoffRunReference,
  HandoffSourceView,
  PhaseHandoffView,
} from '../api/workbench-handoff.js';

export interface WorkbenchHandoffState {
  current: PhaseHandoffView | null;
  draft: HandoffEditableContent;
  source: HandoffSourceView | null;
  conflict: PhaseHandoffView | null;
  dirty: boolean;
  readOnly: boolean;
  keepCurrentDismissed: boolean;
}

export function createWorkbenchHandoffState(
  current: PhaseHandoffView | null = null,
  source: HandoffSourceView | null = null,
  readOnly = false,
): WorkbenchHandoffState {
  const safeCurrent = current ? copyHandoffView(current) : null;
  return {
    current: safeCurrent,
    draft: safeCurrent ? copyHandoffContent(safeCurrent) : emptyHandoffContent(),
    source: source ? copyHandoffSource(source) : null,
    conflict: null,
    dirty: false,
    readOnly: readOnly || Boolean(safeCurrent?.readOnly),
    keepCurrentDismissed: false,
  };
}

export function applyWorkbenchHandoffDraft(
  state: WorkbenchHandoffState,
  draft: HandoffEditableContent,
): WorkbenchHandoffState {
  if (state.readOnly) return state;
  const copied = copyHandoffContent(draft);
  return {
    ...state,
    draft: copied,
    dirty: !sameHandoffContent(copied, state.current),
  };
}

export function applyWorkbenchHandoffSave(
  state: WorkbenchHandoffState,
  saved: PhaseHandoffView,
): WorkbenchHandoffState {
  const current = copyHandoffView(saved);
  return {
    ...state,
    current,
    draft: copyHandoffContent(current),
    conflict: null,
    dirty: false,
    readOnly: state.readOnly || current.readOnly,
  };
}

export function recordWorkbenchHandoffConflict(
  state: WorkbenchHandoffState,
  current: PhaseHandoffView | null,
): WorkbenchHandoffState {
  return {
    ...state,
    conflict: current ? copyHandoffView(current) : null,
  };
}

export function adoptWorkbenchHandoffConflict(state: WorkbenchHandoffState): WorkbenchHandoffState {
  return state.conflict ? applyWorkbenchHandoffSave(state, state.conflict) : state;
}

export function replaceWorkbenchHandoffSource(
  state: WorkbenchHandoffState,
  source: HandoffSourceView | null,
): WorkbenchHandoffState {
  return {
    ...state,
    source: source ? copyHandoffSource(source) : null,
    keepCurrentDismissed: false,
  };
}

export function acceptWorkbenchHandoffSource(
  state: WorkbenchHandoffState,
  reception: HandoffReceptionView,
): WorkbenchHandoffState {
  const source = state.source;
  if (state.readOnly || !source || !source.latestSource) return state;
  const latest = source.latestSource;
  if (
    latest.sourcePhase !== reception.sourcePhase ||
    latest.version !== reception.sourceVersion ||
    latest.contentHash !== reception.sourceHash
  ) {
    return state;
  }
  return {
    ...state,
    source: {
      ...copyHandoffSource(source),
      reception: copyReception(reception),
      acceptedSource: copyHandoffView(latest),
      stale: false,
      diff: null,
    },
    keepCurrentDismissed: false,
  };
}

export function keepCurrentWorkbenchHandoffSource(state: WorkbenchHandoffState): WorkbenchHandoffState {
  return state.readOnly || !state.source ? state : { ...state, keepCurrentDismissed: true };
}

export function addWorkbenchHandoffPinnedFile(
  state: WorkbenchHandoffState,
  reference: HandoffDocumentReference,
): WorkbenchHandoffState {
  if (state.readOnly) return state;
  const key = handoffDocumentKey(reference);
  if (state.draft.pinnedFiles.some((item) => handoffDocumentKey(item) === key)) return state;
  return applyWorkbenchHandoffDraft(state, {
    ...state.draft,
    pinnedFiles: [...state.draft.pinnedFiles, { ...reference }],
  });
}

export function removeWorkbenchHandoffPinnedFile(state: WorkbenchHandoffState, key: string): WorkbenchHandoffState {
  if (state.readOnly) return state;
  return applyWorkbenchHandoffDraft(state, {
    ...state.draft,
    pinnedFiles: state.draft.pinnedFiles.filter((item) => handoffDocumentKey(item) !== key),
  });
}

export function addWorkbenchHandoffRun(
  state: WorkbenchHandoffState,
  reference: HandoffRunReference,
): WorkbenchHandoffState {
  if (state.readOnly) return state;
  const key = handoffRunKey(reference);
  if (state.draft.referencedRuns.some((item) => handoffRunKey(item) === key)) return state;
  return applyWorkbenchHandoffDraft(state, {
    ...state.draft,
    referencedRuns: [...state.draft.referencedRuns, { ...reference }],
  });
}

export function removeWorkbenchHandoffRun(state: WorkbenchHandoffState, key: string): WorkbenchHandoffState {
  if (state.readOnly) return state;
  return applyWorkbenchHandoffDraft(state, {
    ...state.draft,
    referencedRuns: state.draft.referencedRuns.filter((item) => handoffRunKey(item) !== key),
  });
}

export function handoffDocumentKey(reference: HandoffDocumentReference): string {
  return `${reference.repositoryKey}\u0000${reference.relativePath}`;
}

export function handoffRunKey(reference: HandoffRunReference): string {
  return reference.runId;
}

export function copyHandoffContent(source: HandoffEditableContent): HandoffEditableContent {
  return {
    summary: source.summary,
    decisions: source.decisions.map((item) => ({ ...item })),
    openQuestions: source.openQuestions.map((item) => ({ ...item })),
    pinnedFiles: source.pinnedFiles.map((item) => ({ ...item })),
    referencedRuns: source.referencedRuns.map((item) => ({ ...item })),
  };
}

function emptyHandoffContent(): HandoffEditableContent {
  return {
    summary: '',
    decisions: [],
    openQuestions: [],
    pinnedFiles: [],
    referencedRuns: [],
  };
}

function sameHandoffContent(left: HandoffEditableContent, right: HandoffEditableContent | null): boolean {
  return (
    Boolean(right) &&
    JSON.stringify(copyHandoffContent(left)) === JSON.stringify(copyHandoffContent(right as HandoffEditableContent))
  );
}

function copyHandoffView(source: PhaseHandoffView): PhaseHandoffView {
  return { ...source, ...copyHandoffContent(source) };
}

function copyReception(source: HandoffReceptionView): HandoffReceptionView {
  return { ...source };
}

function copyHandoffSource(source: HandoffSourceView): HandoffSourceView {
  return {
    ...source,
    latestSource: source.latestSource ? copyHandoffView(source.latestSource) : null,
    reception: source.reception ? copyReception(source.reception) : null,
    acceptedSource: source.acceptedSource ? copyHandoffView(source.acceptedSource) : null,
    diff: source.diff
      ? {
          summaryChanged: source.diff.summaryChanged,
          decisions: { ...source.diff.decisions },
          openQuestions: { ...source.diff.openQuestions },
          pinnedFiles: { ...source.diff.pinnedFiles },
          referencedRuns: { ...source.diff.referencedRuns },
        }
      : null,
  };
}
