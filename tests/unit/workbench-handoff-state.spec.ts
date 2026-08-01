/**
 * TD-07 Handoff 人工草稿、冲突与 Reception 状态语义。
 *
 * @author alex
 * @since 2026-08-01
 */
import { describe, expect, it } from "vitest";
import type {
  HandoffReceptionView,
  HandoffSourceView,
  PhaseHandoffView,
} from "../../frontend/js/api/workbench-handoff.js";
import {
  acceptWorkbenchHandoffSource,
  addWorkbenchHandoffPinnedFile,
  addWorkbenchHandoffRun,
  applyWorkbenchHandoffDraft,
  applyWorkbenchHandoffSave,
  createWorkbenchHandoffState,
  handoffDocumentKey,
  handoffRunKey,
  keepCurrentWorkbenchHandoffSource,
  recordWorkbenchHandoffConflict,
  removeWorkbenchHandoffPinnedFile,
  replaceWorkbenchHandoffSource,
} from "../../frontend/js/lib/workbench-handoff-state.js";

const HASH_V1 = "a".repeat(64);
const HASH_V2 = "b".repeat(64);

function view(overrides: Partial<PhaseHandoffView> = {}): PhaseHandoffView {
  return {
    sourcePhase: "REQUIREMENT_ANALYSIS",
    summary: "初始摘要",
    decisions: [{ text: "初始决定", rationale: null }],
    openQuestions: [{ text: "初始问题", ownerHint: null }],
    pinnedFiles: [{ repositoryKey: "agent-web", relativePath: "README.md" }],
    referencedRuns: [
      { runId: "run-1", phase: "REQUIREMENT_ANALYSIS", safeSummary: null },
    ],
    version: 3,
    contentHash: HASH_V1,
    updatedAt: 1_722_528_000_000,
    readOnly: false,
    ...overrides,
  };
}

function source(overrides: Partial<HandoffSourceView> = {}): HandoffSourceView {
  return {
    targetPhase: "SOLUTION_DESIGN",
    latestSource: view(),
    reception: null,
    acceptedSource: null,
    stale: false,
    diff: null,
    ...overrides,
  };
}

describe("workbench handoff state", () => {
  it("creates a deep editable copy of all five fields without mutating the loaded view", () => {
    const loaded = view();
    let state = createWorkbenchHandoffState(loaded, source(), false);
    const draft = {
      ...state.draft,
      summary: "本地摘要",
      decisions: [{ text: "本地决定", rationale: "本地理由" }],
    };

    state = applyWorkbenchHandoffDraft(state, draft);

    expect(state.draft).toEqual(
      expect.objectContaining({
        summary: "本地摘要",
        decisions: [{ text: "本地决定", rationale: "本地理由" }],
        openQuestions: [{ text: "初始问题", ownerHint: null }],
        pinnedFiles: [
          { repositoryKey: "agent-web", relativePath: "README.md" },
        ],
        referencedRuns: [
          { runId: "run-1", phase: "REQUIREMENT_ANALYSIS", safeSummary: null },
        ],
      }),
    );
    expect(state.dirty).toBe(true);
    expect(loaded.summary).toBe("初始摘要");
    expect(loaded.decisions).toEqual([{ text: "初始决定", rationale: null }]);
  });

  it("uses the saved projection as the new baseline and clears dirty/conflict", () => {
    let state = createWorkbenchHandoffState(view(), source(), false);
    state = applyWorkbenchHandoffDraft(state, {
      ...state.draft,
      summary: "本地摘要",
    });
    state = recordWorkbenchHandoffConflict(
      state,
      view({ version: 4, contentHash: HASH_V2 }),
    );

    state = applyWorkbenchHandoffSave(
      state,
      view({
        summary: "本地摘要",
        version: 5,
        contentHash: "c".repeat(64),
      }),
    );

    expect(state.current).toEqual(
      expect.objectContaining({ version: 5, summary: "本地摘要" }),
    );
    expect(state.draft.summary).toBe("本地摘要");
    expect(state.dirty).toBe(false);
    expect(state.conflict).toBeNull();
  });

  it("retains the local draft on conflict while exposing a safe remote current projection", () => {
    let state = createWorkbenchHandoffState(view(), source(), false);
    state = applyWorkbenchHandoffDraft(state, {
      ...state.draft,
      summary: "不能丢失的本地草稿",
    });
    const remote = view({
      summary: "远端摘要",
      version: 4,
      contentHash: HASH_V2,
    });

    state = recordWorkbenchHandoffConflict(state, remote);

    expect(state.draft.summary).toBe("不能丢失的本地草稿");
    expect(state.dirty).toBe(true);
    expect(state.conflict).toEqual(remote);
    expect(state.current?.version).toBe(3);
  });

  it("updates stale source data without overwriting this phase draft", () => {
    let state = createWorkbenchHandoffState(view(), source(), false);
    state = applyWorkbenchHandoffDraft(state, {
      ...state.draft,
      summary: "本阶段草稿",
    });
    const updatedSource = source({
      latestSource: view({
        version: 4,
        contentHash: HASH_V2,
        summary: "上游新版本",
      }),
      stale: true,
      diff: {
        summaryChanged: true,
        decisions: { added: 1, removed: 0 },
        openQuestions: { added: 0, removed: 0 },
        pinnedFiles: { added: 0, removed: 0 },
        referencedRuns: { added: 0, removed: 0 },
      },
    });

    state = replaceWorkbenchHandoffSource(state, updatedSource);

    expect(state.draft.summary).toBe("本阶段草稿");
    expect(state.source?.stale).toBe(true);
    expect(state.keepCurrentDismissed).toBe(false);
  });

  it("accepts an exact latest source but keep-current only dismisses the local prompt", () => {
    const oldReception: HandoffReceptionView = {
      sourcePhase: "REQUIREMENT_ANALYSIS",
      sourceVersion: 2,
      sourceHash: "d".repeat(64),
      acceptedAt: 1_722_528_000_000,
    };
    let state = createWorkbenchHandoffState(
      view(),
      source({
        reception: oldReception,
        acceptedSource: view({ version: 2, contentHash: "d".repeat(64) }),
        stale: true,
      }),
      false,
    );
    const beforeDraft = state.draft;

    state = keepCurrentWorkbenchHandoffSource(state);
    expect(state.keepCurrentDismissed).toBe(true);
    expect(state.source?.reception).toEqual(oldReception);
    expect(state.source?.stale).toBe(true);
    expect(state.draft).toEqual(beforeDraft);

    const accepted: HandoffReceptionView = {
      sourcePhase: "REQUIREMENT_ANALYSIS",
      sourceVersion: 3,
      sourceHash: HASH_V1,
      acceptedAt: 1_722_528_000_100,
    };
    state = acceptWorkbenchHandoffSource(state, accepted);
    expect(state.source).toEqual(
      expect.objectContaining({
        reception: accepted,
        acceptedSource: expect.objectContaining({
          version: 3,
          contentHash: HASH_V1,
        }),
        stale: false,
        diff: null,
      }),
    );
    expect(state.draft).toEqual(beforeDraft);
  });

  it("keeps archived/read-only state immutable and uses stable reference keys", () => {
    let state = createWorkbenchHandoffState(
      view({ readOnly: true }),
      source(),
      true,
    );
    state = applyWorkbenchHandoffDraft(state, {
      ...state.draft,
      summary: "禁止修改",
    });
    state = addWorkbenchHandoffPinnedFile(state, {
      repositoryKey: "agent-web",
      relativePath: "docs/next.md",
    });
    state = addWorkbenchHandoffRun(state, {
      runId: "run-2",
      phase: "REQUIREMENT_ANALYSIS",
      safeSummary: null,
    });
    state = keepCurrentWorkbenchHandoffSource(state);

    expect(state.draft.summary).toBe("初始摘要");
    expect(state.draft.pinnedFiles).toHaveLength(1);
    expect(state.draft.referencedRuns).toHaveLength(1);
    expect(state.keepCurrentDismissed).toBe(false);
    expect(
      handoffDocumentKey({
        repositoryKey: "agent-web",
        relativePath: "docs/next.md",
      }),
    ).toBe("agent-web\u0000docs/next.md");
    expect(
      handoffRunKey({
        runId: "run-2",
        phase: "REQUIREMENT_ANALYSIS",
        safeSummary: null,
      }),
    ).toBe("run-2");

    const editable = createWorkbenchHandoffState(view(), source(), false);
    const added = addWorkbenchHandoffPinnedFile(editable, {
      repositoryKey: "agent-web",
      relativePath: "docs/next.md",
    });
    const duplicate = addWorkbenchHandoffPinnedFile(added, {
      repositoryKey: "agent-web",
      relativePath: "docs/next.md",
    });
    const removed = removeWorkbenchHandoffPinnedFile(
      duplicate,
      "agent-web\u0000docs/next.md",
    );
    expect(added.draft.pinnedFiles).toHaveLength(2);
    expect(duplicate.draft.pinnedFiles).toHaveLength(2);
    expect(removed.draft.pinnedFiles).toEqual(view().pinnedFiles);
  });
});
