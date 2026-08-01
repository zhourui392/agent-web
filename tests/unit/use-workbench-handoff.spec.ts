/**
 * TD-07 Handoff 加载、保存与接收编排。
 *
 * @author alex
 * @since 2026-08-01
 */
// @ts-expect-error 前端 Vue ESM 入口未为测试工程的相对路径暴露声明文件。
import * as frontendVueRuntime from "../../frontend/node_modules/vue/index.mjs";
import { describe, expect, it, vi } from "vitest";
import {
  WorkbenchHandoffApiError,
  type HandoffSourceView,
  type PhaseHandoffView,
  type WorkbenchHandoffApiClient,
} from "../../frontend/js/api/workbench-handoff.js";
import { useWorkbenchHandoff } from "../../frontend/js/composables/useWorkbenchHandoff.js";

const { nextTick, ref } = frontendVueRuntime as typeof import("vue");
const HASH_V1 = "a".repeat(64);
const HASH_V2 = "b".repeat(64);

function view(overrides: Partial<PhaseHandoffView> = {}): PhaseHandoffView {
  return {
    sourcePhase: "REQUIREMENT_ANALYSIS",
    summary: "初始摘要",
    decisions: [],
    openQuestions: [],
    pinnedFiles: [],
    referencedRuns: [],
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

function api(
  overrides: Partial<WorkbenchHandoffApiClient> = {},
): WorkbenchHandoffApiClient {
  return {
    getHandoff: vi.fn().mockResolvedValue(view()),
    putHandoff: vi
      .fn()
      .mockResolvedValue(view({ version: 4, contentHash: HASH_V2 })),
    getHandoffSource: vi.fn().mockResolvedValue(source()),
    acceptHandoffReception: vi.fn().mockResolvedValue({
      sourcePhase: "REQUIREMENT_ANALYSIS",
      sourceVersion: 3,
      sourceHash: HASH_V1,
      acceptedAt: 1_722_528_000_100,
    }),
    ...overrides,
  };
}

describe("useWorkbenchHandoff", () => {
  it("loads this phase handoff and its upstream source on identity/phase changes", async () => {
    const workbenchId = ref<string | null>("wb-1");
    const phase = ref<"SOLUTION_DESIGN" | "IMPLEMENT_TEST">("SOLUTION_DESIGN");
    const client = api();
    const handoff = useWorkbenchHandoff({
      workbenchId,
      phase,
      apiClient: client,
    });

    expect(handoff.handoffDrawerVisible.value).toBe(false);
    handoff.openHandoffDrawer();
    expect(handoff.handoffDrawerVisible.value).toBe(true);

    await vi.waitFor(() =>
      expect(client.getHandoff).toHaveBeenCalledWith("wb-1", "SOLUTION_DESIGN"),
    );
    expect(client.getHandoffSource).toHaveBeenCalledWith(
      "wb-1",
      "SOLUTION_DESIGN",
    );

    phase.value = "IMPLEMENT_TEST";
    await nextTick();
    await vi.waitFor(() =>
      expect(client.getHandoff).toHaveBeenLastCalledWith(
        "wb-1",
        "IMPLEMENT_TEST",
      ),
    );
    expect(client.getHandoffSource).toHaveBeenLastCalledWith(
      "wb-1",
      "IMPLEMENT_TEST",
    );
    expect(handoff.handoffDrawerVisible.value).toBe(true);
    handoff.closeHandoffDrawer();
    expect(handoff.handoffDrawerVisible.value).toBe(false);
  });

  it("saves the five-field local draft against the currently loaded version", async () => {
    const client = api();
    const handoff = useWorkbenchHandoff({
      workbenchId: ref("wb-1"),
      phase: ref("REQUIREMENT_ANALYSIS"),
      apiClient: client,
    });
    await vi.waitFor(() => expect(handoff.handoffLoading.value).toBe(false));
    handoff.updateHandoffDraft({
      ...handoff.handoffDraft.value,
      summary: "本地草稿",
    });

    await handoff.saveHandoff();

    expect(client.putHandoff).toHaveBeenCalledWith(
      "wb-1",
      "REQUIREMENT_ANALYSIS",
      3,
      expect.objectContaining({
        summary: "本地草稿",
        decisions: [],
        openQuestions: [],
        pinnedFiles: [],
        referencedRuns: [],
      }),
    );
    expect(handoff.handoffCurrent.value?.version).toBe(4);
    expect(handoff.handoffDirty.value).toBe(false);
  });

  it("retains local draft and publishes only the safe current projection on 409", async () => {
    const remote = view({
      summary: "远端摘要",
      version: 4,
      contentHash: HASH_V2,
    });
    const client = api({
      putHandoff: vi
        .fn()
        .mockRejectedValue(
          new WorkbenchHandoffApiError(
            409,
            "WORKBENCH_HANDOFF_VERSION_CONFLICT",
            remote,
          ),
        ),
    });
    const handoff = useWorkbenchHandoff({
      workbenchId: ref("wb-1"),
      phase: ref("REQUIREMENT_ANALYSIS"),
      apiClient: client,
    });
    await vi.waitFor(() => expect(handoff.handoffLoading.value).toBe(false));
    handoff.updateHandoffDraft({
      ...handoff.handoffDraft.value,
      summary: "不能丢的本地草稿",
    });

    await handoff.saveHandoff();

    expect(handoff.handoffDraft.value.summary).toBe("不能丢的本地草稿");
    expect(handoff.handoffConflict.value).toEqual(remote);
    expect(handoff.handoffError.value).toBe(
      "交接内容已被其他页面更新，本地草稿仍保留，请对比后处理。",
    );
    expect(handoff.handoffDirty.value).toBe(true);
  });

  it("accepts the exact latest version while keep-current performs no API mutation", async () => {
    const latest = view({ sourcePhase: "REQUIREMENT_ANALYSIS" });
    const client = api({
      getHandoffSource: vi
        .fn()
        .mockResolvedValue(source({ latestSource: latest })),
    });
    const handoff = useWorkbenchHandoff({
      workbenchId: ref("wb-1"),
      phase: ref("SOLUTION_DESIGN"),
      apiClient: client,
    });
    await vi.waitFor(() => expect(handoff.handoffLoading.value).toBe(false));

    handoff.keepCurrentSource();
    expect(client.acceptHandoffReception).not.toHaveBeenCalled();
    expect(handoff.keepCurrentDismissed.value).toBe(true);

    await handoff.acceptLatestSource();
    expect(client.acceptHandoffReception).toHaveBeenCalledWith(
      "wb-1",
      "SOLUTION_DESIGN",
      {
        sourcePhase: "REQUIREMENT_ANALYSIS",
        sourceVersion: 3,
        sourceHash: HASH_V1,
      },
    );
    expect(handoff.handoffSource.value?.stale).toBe(false);
    expect(handoff.handoffSource.value?.reception?.sourceVersion).toBe(3);
  });

  it("allows archived workbenches to load but never save or accept", async () => {
    const client = api();
    const handoff = useWorkbenchHandoff({
      workbenchId: ref("wb-1"),
      phase: ref("SOLUTION_DESIGN"),
      archived: ref(true),
      apiClient: client,
    });
    await vi.waitFor(() => expect(handoff.handoffLoading.value).toBe(false));

    handoff.updateHandoffDraft({
      ...handoff.handoffDraft.value,
      summary: "禁止写入",
    });
    await handoff.saveHandoff();
    await handoff.acceptLatestSource();

    expect(client.getHandoff).toHaveBeenCalled();
    expect(client.getHandoffSource).toHaveBeenCalled();
    expect(client.putHandoff).not.toHaveBeenCalled();
    expect(client.acceptHandoffReception).not.toHaveBeenCalled();
    expect(handoff.handoffDraft.value.summary).toBe("初始摘要");
    expect(handoff.handoffReadOnly.value).toBe(true);
  });

  it("serializes save and reception mutations so loading flags cannot be orphaned", async () => {
    let resolveSave: ((value: PhaseHandoffView) => void) | null = null;
    const putHandoff = vi.fn(
      () =>
        new Promise<PhaseHandoffView>((resolve) => {
          resolveSave = resolve;
        }),
    );
    const client = api({ putHandoff });
    const handoff = useWorkbenchHandoff({
      workbenchId: ref("wb-1"),
      phase: ref("SOLUTION_DESIGN"),
      apiClient: client,
    });
    await vi.waitFor(() => expect(handoff.handoffLoading.value).toBe(false));
    handoff.updateHandoffDraft({
      ...handoff.handoffDraft.value,
      summary: "等待保存",
    });

    const saving = handoff.saveHandoff();
    expect(handoff.handoffSaving.value).toBe(true);
    await handoff.acceptLatestSource();

    expect(client.acceptHandoffReception).not.toHaveBeenCalled();
    const saveResolver = resolveSave as
      ((value: PhaseHandoffView) => void) | null;
    expect(saveResolver).not.toBeNull();
    saveResolver?.(
      view({ summary: "等待保存", version: 4, contentHash: HASH_V2 }),
    );
    await saving;
    expect(handoff.handoffSaving.value).toBe(false);
  });

  it("ignores an old scope response after switching workbench identity", async () => {
    let resolveOld: ((value: PhaseHandoffView | null) => void) | null = null;
    const getHandoff = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<PhaseHandoffView | null>((resolve) => {
            resolveOld = resolve;
          }),
      )
      .mockResolvedValueOnce(view({ summary: "新 Workbench" }));
    const getHandoffSource = vi
      .fn()
      .mockResolvedValueOnce(source())
      .mockResolvedValueOnce(source());
    const client = api({ getHandoff, getHandoffSource });
    const workbenchId = ref<string | null>("wb-old");
    const handoff = useWorkbenchHandoff({
      workbenchId,
      phase: ref("SOLUTION_DESIGN"),
      apiClient: client,
    });

    workbenchId.value = "wb-new";
    await nextTick();
    await vi.waitFor(() =>
      expect(handoff.handoffCurrent.value?.summary).toBe("新 Workbench"),
    );
    const oldResolver = resolveOld as
      ((value: PhaseHandoffView | null) => void) | null;
    expect(oldResolver).not.toBeNull();
    oldResolver?.(view({ summary: "旧响应不得覆盖" }));
    await nextTick();

    expect(handoff.handoffCurrent.value?.summary).toBe("新 Workbench");
    expect(handoff.handoffLoading.value).toBe(false);
  });
});
