/**
 * TD-05 Capability Drawer 状态与 NEXT_RUN 语义。
 *
 * @author alex
 * @since 2026-08-01
 */
// Vitest 工程与 frontend 各自安装依赖；状态测试必须复用 composable 实际加载的 Vue 实例。
// @ts-expect-error Vue 的直接 ESM 入口没有为相对路径暴露声明文件。
import * as frontendVueRuntime from '../../frontend/node_modules/vue/index.mjs';
import { describe, expect, it, vi } from 'vitest';
import type {
  WorkbenchCapabilityApiClient,
  WorkbenchCapabilityMutationResult,
  WorkbenchEffectiveCapabilityProfile,
  WorkbenchPhaseCapabilityOverride,
} from '../../frontend/js/api/workbench-capability.js';
import { useWorkbenchCapability } from '../../frontend/js/composables/useWorkbenchCapability.js';

const { nextTick, ref } = frontendVueRuntime as typeof import('vue');

const PROFILE: WorkbenchEffectiveCapabilityProfile = {
  phase: 'IMPLEMENT_TEST',
  status: 'AVAILABLE',
  profileId: 'workbench-implement-test',
  profileVersion: '1.0.0',
  profileHash: 'a'.repeat(64),
  rules: [{
    id: 'platform/workbench-safety',
    required: true,
    selected: true,
    source: 'PHASE_PROFILE',
    summary: '平台安全规则',
  }],
  skills: [{
    id: 'java-tdd',
    required: false,
    selected: true,
    source: 'PHASE_PROFILE',
    summary: 'Java TDD',
  }],
  mcpServers: [{
    id: 'repository-query',
    required: false,
    selected: false,
    source: 'PHASE_PROFILE',
    summary: '只读仓库查询',
  }],
  optionalSkillIds: ['java-tdd'],
  optionalMcpServerIds: [],
  additionalRule: '',
  overrideVersion: 0,
  warnings: [],
  effectiveFrom: 'NEXT_RUN',
  activeRunSnapshotHash: 'b'.repeat(64),
};

const OVERRIDE: WorkbenchPhaseCapabilityOverride = {
  version: 3,
  optionalSkillIds: ['java-tdd'],
  optionalMcpServerIds: ['repository-query'],
  additionalRule: '先运行聚焦测试',
  updatedAt: 1_722_528_000_000,
};

function api(
  overrides: Partial<WorkbenchCapabilityApiClient> = {},
): WorkbenchCapabilityApiClient {
  return {
    getEffectiveProfile: vi.fn().mockResolvedValue(PROFILE),
    getOverride: vi.fn().mockResolvedValue(OVERRIDE),
    putOverride: vi.fn().mockResolvedValue({
      version: 4,
      effectiveFrom: 'NEXT_RUN',
      activeRunSnapshotHash: 'c'.repeat(64),
    }),
    deleteOverride: vi.fn().mockResolvedValue({
      version: 0,
      effectiveFrom: 'NEXT_RUN',
      activeRunSnapshotHash: 'c'.repeat(64),
    }),
    ...overrides,
  };
}

describe('useWorkbenchCapability', () => {
  it('loads profile and override when opened and isolates state by workbench and phase', async () => {
    const workbenchId = ref<string | null>('wb-1');
    const phase = ref<'IMPLEMENT_TEST' | 'REVIEW_REFACTOR'>('IMPLEMENT_TEST');
    const client = api();
    const capability = useWorkbenchCapability({ workbenchId, phase, apiClient: client });

    await capability.openCapabilityDrawer();

    expect(capability.capabilityDrawerVisible.value).toBe(true);
    expect(client.getEffectiveProfile).toHaveBeenCalledWith('wb-1', 'IMPLEMENT_TEST');
    expect(client.getOverride).toHaveBeenCalledWith('wb-1', 'IMPLEMENT_TEST');
    expect(capability.capabilityDraft.value).toEqual({
      optionalSkillIds: ['java-tdd'],
      optionalMcpServerIds: ['repository-query'],
      additionalRule: '先运行聚焦测试',
    });

    phase.value = 'REVIEW_REFACTOR';
    await nextTick();
    await nextTick();

    expect(client.getEffectiveProfile).toHaveBeenLastCalledWith('wb-1', 'REVIEW_REFACTOR');
    expect(client.getOverride).toHaveBeenLastCalledWith('wb-1', 'REVIEW_REFACTOR');
  });

  it('saves only the next-run override and retains the active binding hash as evidence', async () => {
    const client = api();
    const capability = useWorkbenchCapability({
      workbenchId: ref('wb-1'),
      phase: ref('IMPLEMENT_TEST'),
      apiClient: client,
    });
    await capability.openCapabilityDrawer();
    capability.updateCapabilityDraft({
      optionalSkillIds: [],
      optionalMcpServerIds: ['repository-query'],
      additionalRule: '最小修改并保留回滚路径',
    });

    await capability.saveCapabilityOverride();

    expect(client.putOverride).toHaveBeenCalledWith('wb-1', 'IMPLEMENT_TEST', 3, {
      optionalSkillIds: [],
      optionalMcpServerIds: ['repository-query'],
      additionalRule: '最小修改并保留回滚路径',
    });
    expect(capability.capabilityMutation.value).toEqual({
      kind: 'SAVED',
      effectiveFrom: 'NEXT_RUN',
      activeRunSnapshotHash: 'c'.repeat(64),
    });
    expect(capability.capabilityOverride.value?.version).toBe(4);
    expect(capability.capabilityNotice.value).toContain('仅对下一轮运行生效');
    expect(capability.capabilityNotice.value).toContain('cccccccccccc');
  });

  it('restores defaults with the current version and never mutates an active snapshot', async () => {
    const refreshedProfile = {
      ...PROFILE,
      activeRunSnapshotHash: 'f'.repeat(64),
    };
    const client = api({
      getEffectiveProfile: vi.fn()
        .mockResolvedValueOnce(PROFILE)
        .mockResolvedValueOnce(refreshedProfile),
    });
    const capability = useWorkbenchCapability({
      workbenchId: ref('wb-1'),
      phase: ref('IMPLEMENT_TEST'),
      apiClient: client,
    });
    await capability.openCapabilityDrawer();

    await capability.restoreCapabilityDefaults();

    expect(client.deleteOverride).toHaveBeenCalledWith('wb-1', 'IMPLEMENT_TEST', 3);
    expect(capability.capabilityOverride.value).toBeNull();
    expect(capability.capabilityDraft.value).toEqual({
      optionalSkillIds: ['java-tdd'],
      optionalMcpServerIds: [],
      additionalRule: '',
    });
    expect(capability.capabilityMutation.value?.effectiveFrom).toBe('NEXT_RUN');
    expect(capability.capabilityMutation.value?.activeRunSnapshotHash).toBe('c'.repeat(64));
    expect(capability.capabilityProfile.value?.activeRunSnapshotHash).toBe('f'.repeat(64));
    expect(capability.capabilityNotice.value).toContain('已恢复默认');
  });

  it('maps a stale If-Match conflict to a safe refresh instruction', async () => {
    const client = api({
      putOverride: vi.fn().mockRejectedValue({
        status: 409,
        code: 'WORKBENCH_CAPABILITY_VERSION_CONFLICT',
      }),
    });
    const capability = useWorkbenchCapability({
      workbenchId: ref('wb-1'),
      phase: ref('IMPLEMENT_TEST'),
      apiClient: client,
    });
    await capability.openCapabilityDrawer();

    await capability.saveCapabilityOverride();

    expect(capability.capabilityError.value).toBe('能力配置已被其他页面更新，请刷新后重试。');
    expect(capability.capabilitySaving.value).toBe(false);
  });

  it('does not let an old-phase mutation clear a new-phase saving state', async () => {
    let resolveOld: ((result: WorkbenchCapabilityMutationResult) => void) | null = null;
    let resolveCurrent: ((result: WorkbenchCapabilityMutationResult) => void) | null = null;
    const putOverride = vi.fn()
      .mockImplementationOnce(() => new Promise<WorkbenchCapabilityMutationResult>((resolve) => {
        resolveOld = resolve;
      }))
      .mockImplementationOnce(() => new Promise<WorkbenchCapabilityMutationResult>((resolve) => {
        resolveCurrent = resolve;
      }));
    const client = api({ putOverride });
    const phase = ref<'IMPLEMENT_TEST' | 'REVIEW_REFACTOR'>('IMPLEMENT_TEST');
    const capability = useWorkbenchCapability({
      workbenchId: ref('wb-1'),
      phase,
      apiClient: client,
    });
    await capability.openCapabilityDrawer();

    const oldSave = capability.saveCapabilityOverride();
    phase.value = 'REVIEW_REFACTOR';
    await vi.waitFor(() => expect(capability.capabilityProfile.value).not.toBeNull());
    const currentSave = capability.saveCapabilityOverride();
    expect(capability.capabilitySaving.value).toBe(true);

    const oldResult = resolveOld as ((result: WorkbenchCapabilityMutationResult) => void) | null;
    expect(oldResult).not.toBeNull();
    oldResult?.({
      version: 4,
      effectiveFrom: 'NEXT_RUN',
      activeRunSnapshotHash: 'd'.repeat(64),
    });
    await oldSave;

    expect(capability.capabilitySaving.value).toBe(true);
    expect(capability.capabilityMutation.value).toBeNull();

    const currentResult = resolveCurrent as (
      (result: WorkbenchCapabilityMutationResult) => void
    ) | null;
    expect(currentResult).not.toBeNull();
    currentResult?.({
      version: 5,
      effectiveFrom: 'NEXT_RUN',
      activeRunSnapshotHash: 'e'.repeat(64),
    });
    await currentSave;

    expect(capability.capabilitySaving.value).toBe(false);
    expect(capability.capabilityMutation.value?.activeRunSnapshotHash).toBe('e'.repeat(64));
  });
});
