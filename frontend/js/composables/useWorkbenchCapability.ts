/**
 * TD-05 Workbench Capability Drawer 状态编排。
 *
 * @author alex
 * @since 2026-08-01
 */
import {
  computed,
  ref,
  watch,
  type Ref,
} from 'vue';
import {
  WorkbenchCapabilityApiError,
  createWorkbenchCapabilityApiClient,
  type WorkbenchCapabilityApiClient,
  type WorkbenchCapabilityMutationResult,
  type WorkbenchCapabilityOverrideInput,
  type WorkbenchEffectiveCapabilityProfile,
  type WorkbenchPhaseCapabilityOverride,
} from '../api/workbench-capability.js';
import type { WorkbenchPhase } from '../lib/workbench-state.js';

export interface UseWorkbenchCapabilityOptions {
  workbenchId: Ref<string | null>;
  phase: Ref<WorkbenchPhase>;
  apiClient?: WorkbenchCapabilityApiClient;
}

export interface WorkbenchCapabilityMutationNotice {
  kind: 'SAVED' | 'DEFAULTS_RESTORED';
  effectiveFrom: 'NEXT_RUN';
  activeRunSnapshotHash: string | null;
}

export function useWorkbenchCapability(options: UseWorkbenchCapabilityOptions) {
  const apiClient = options.apiClient ?? createWorkbenchCapabilityApiClient();
  const capabilityDrawerVisible = ref(false);
  const capabilityLoading = ref(false);
  const capabilitySaving = ref(false);
  const capabilityError = ref<string | null>(null);
  const capabilityProfile = ref<WorkbenchEffectiveCapabilityProfile | null>(null);
  const capabilityOverride = ref<WorkbenchPhaseCapabilityOverride | null>(null);
  const capabilityDraft = ref<WorkbenchCapabilityOverrideInput>(emptyDraft());
  const capabilityMutation = ref<WorkbenchCapabilityMutationNotice | null>(null);
  let loadGeneration = 0;
  let mutationGeneration = 0;

  const capabilityNotice = computed(() => {
    const mutation = capabilityMutation.value;
    if (!mutation) return null;
    const binding = mutation.activeRunSnapshotHash;
    const action = mutation.kind === 'DEFAULTS_RESTORED'
      ? '能力覆盖已恢复默认'
      : '能力覆盖已更新';
    return binding
      ? `${action}，仅对下一轮运行生效；当前运行继续使用绑定 ${binding.slice(0, 12)}。`
      : `${action}，仅对下一轮运行生效；当前没有活动运行快照。`;
  });

  const capabilityDirty = computed(() => {
    const baseline = capabilityOverride.value ?? capabilityProfile.value;
    if (!baseline) return false;
    return !sameSelection(capabilityDraft.value, baseline);
  });

  const capabilityCanRestoreDefaults = computed(() => capabilityOverride.value != null);

  async function openCapabilityDrawer(): Promise<void> {
    capabilityDrawerVisible.value = true;
    capabilityMutation.value = null;
    await loadCapability();
  }

  function closeCapabilityDrawer(): void {
    capabilityDrawerVisible.value = false;
  }

  async function refreshCapability(): Promise<void> {
    capabilityMutation.value = null;
    await loadCapability();
  }

  async function loadCapability(): Promise<void> {
    const identity = currentIdentity();
    if (!identity) {
      resetCapability();
      return;
    }
    const generation = ++loadGeneration;
    capabilityLoading.value = true;
    capabilityError.value = null;
    try {
      const [profile, currentOverride] = await Promise.all([
        apiClient.getEffectiveProfile(identity.workbenchId, identity.phase),
        apiClient.getOverride(identity.workbenchId, identity.phase),
      ]);
      if (generation !== loadGeneration || !sameIdentity(identity)) return;
      capabilityProfile.value = profile;
      capabilityOverride.value = currentOverride;
      capabilityDraft.value = selectionCopy(currentOverride ?? profile);
    } catch (error) {
      if (generation === loadGeneration && sameIdentity(identity)) {
        capabilityError.value = capabilityErrorMessage(error);
        capabilityProfile.value = null;
        capabilityOverride.value = null;
        capabilityDraft.value = emptyDraft();
      }
    } finally {
      if (generation === loadGeneration) capabilityLoading.value = false;
    }
  }

  function updateCapabilityDraft(next: WorkbenchCapabilityOverrideInput): void {
    capabilityDraft.value = selectionCopy(next);
    capabilityMutation.value = null;
    capabilityError.value = null;
  }

  async function saveCapabilityOverride(): Promise<void> {
    const identity = currentIdentity();
    const profile = capabilityProfile.value;
    if (!identity || !profile || capabilitySaving.value) return;
    const expectedVersion = capabilityOverride.value?.version ?? profile.overrideVersion;
    const submitted = selectionCopy(capabilityDraft.value);
    const generation = ++mutationGeneration;
    capabilitySaving.value = true;
    capabilityError.value = null;
    try {
      const result = await apiClient.putOverride(
        identity.workbenchId,
        identity.phase,
        expectedVersion,
        submitted,
      );
      if (generation !== mutationGeneration || !sameIdentity(identity)) return;
      capabilityOverride.value = {
        version: result.version,
        updatedAt: Date.now(),
        ...submitted,
      };
      capabilityProfile.value = applyMutation(profile, submitted, result);
      capabilityMutation.value = mutationNotice('SAVED', result);
    } catch (error) {
      if (generation === mutationGeneration && sameIdentity(identity)) {
        capabilityError.value = capabilityErrorMessage(error);
      }
    } finally {
      if (generation === mutationGeneration) capabilitySaving.value = false;
    }
  }

  async function restoreCapabilityDefaults(): Promise<void> {
    const identity = currentIdentity();
    const currentOverride = capabilityOverride.value;
    if (!identity || !currentOverride || capabilitySaving.value) return;
    const generation = ++mutationGeneration;
    capabilitySaving.value = true;
    capabilityError.value = null;
    try {
      const result = await apiClient.deleteOverride(
        identity.workbenchId,
        identity.phase,
        currentOverride.version,
      );
      if (generation !== mutationGeneration || !sameIdentity(identity)) return;
      const defaultProfile = await apiClient.getEffectiveProfile(
        identity.workbenchId,
        identity.phase,
      );
      if (generation !== mutationGeneration || !sameIdentity(identity)) return;
      capabilityOverride.value = null;
      capabilityProfile.value = defaultProfile;
      capabilityDraft.value = selectionCopy(defaultProfile);
      capabilityMutation.value = mutationNotice('DEFAULTS_RESTORED', result);
    } catch (error) {
      if (generation === mutationGeneration && sameIdentity(identity)) {
        capabilityError.value = capabilityErrorMessage(error);
      }
    } finally {
      if (generation === mutationGeneration) capabilitySaving.value = false;
    }
  }

  function currentIdentity(): { workbenchId: string; phase: WorkbenchPhase } | null {
    const workbenchId = options.workbenchId.value;
    return workbenchId ? { workbenchId, phase: options.phase.value } : null;
  }

  function sameIdentity(identity: { workbenchId: string; phase: WorkbenchPhase }): boolean {
    return options.workbenchId.value === identity.workbenchId
      && options.phase.value === identity.phase;
  }

  function resetCapability(): void {
    loadGeneration++;
    mutationGeneration++;
    capabilityLoading.value = false;
    capabilitySaving.value = false;
    capabilityError.value = null;
    capabilityProfile.value = null;
    capabilityOverride.value = null;
    capabilityDraft.value = emptyDraft();
    capabilityMutation.value = null;
  }

  watch(
    () => `${options.workbenchId.value ?? ''}\u0000${options.phase.value}`,
    () => {
      resetCapability();
      if (capabilityDrawerVisible.value) void loadCapability();
    },
    { flush: 'sync' },
  );

  return {
    capabilityDrawerVisible,
    capabilityLoading,
    capabilitySaving,
    capabilityError,
    capabilityProfile,
    capabilityOverride,
    capabilityDraft,
    capabilityMutation,
    capabilityNotice,
    capabilityDirty,
    capabilityCanRestoreDefaults,
    openCapabilityDrawer,
    closeCapabilityDrawer,
    refreshCapability,
    updateCapabilityDraft,
    saveCapabilityOverride,
    restoreCapabilityDefaults,
  };
}

function emptyDraft(): WorkbenchCapabilityOverrideInput {
  return {
    optionalSkillIds: [],
    optionalMcpServerIds: [],
    additionalRule: '',
  };
}

function selectionCopy(
  source: WorkbenchCapabilityOverrideInput,
): WorkbenchCapabilityOverrideInput {
  return {
    optionalSkillIds: [...source.optionalSkillIds],
    optionalMcpServerIds: [...source.optionalMcpServerIds],
    additionalRule: source.additionalRule,
  };
}

function sameSelection(
  left: WorkbenchCapabilityOverrideInput,
  right: WorkbenchCapabilityOverrideInput,
): boolean {
  return left.additionalRule === right.additionalRule
    && sameIdentifiers(left.optionalSkillIds, right.optionalSkillIds)
    && sameIdentifiers(left.optionalMcpServerIds, right.optionalMcpServerIds);
}

function sameIdentifiers(left: string[], right: string[]): boolean {
  return left.length === right.length
    && [...left].sort().every((value, index) => value === [...right].sort()[index]);
}

function applyMutation(
  profile: WorkbenchEffectiveCapabilityProfile,
  selection: WorkbenchCapabilityOverrideInput,
  result: WorkbenchCapabilityMutationResult,
): WorkbenchEffectiveCapabilityProfile {
  return {
    ...profile,
    ...selectionCopy(selection),
    overrideVersion: result.version,
    effectiveFrom: result.effectiveFrom,
    activeRunSnapshotHash: result.activeRunSnapshotHash,
  };
}

function mutationNotice(
  kind: WorkbenchCapabilityMutationNotice['kind'],
  result: WorkbenchCapabilityMutationResult,
): WorkbenchCapabilityMutationNotice {
  return {
    kind,
    effectiveFrom: result.effectiveFrom,
    activeRunSnapshotHash: result.activeRunSnapshotHash,
  };
}

function capabilityErrorMessage(error: unknown): string {
  const status = error instanceof WorkbenchCapabilityApiError
    ? error.status
    : typeof error === 'object' && error !== null && 'status' in error
      ? (error as { status?: unknown }).status
      : null;
  const code = error instanceof WorkbenchCapabilityApiError
    ? error.code
    : typeof error === 'object' && error !== null && 'code' in error
      ? (error as { code?: unknown }).code
      : null;
  if (status === 409 || code === 'WORKBENCH_CAPABILITY_VERSION_CONFLICT') {
    return '能力配置已被其他页面更新，请刷新后重试。';
  }
  if (code === 'WORKBENCH_PROFILE_UNAVAILABLE') {
    return '当前阶段能力 Profile 暂不可用，请稍后重试。';
  }
  if (status === 403 || code === 'WORKBENCH_CAPABILITY_ESCALATION_DENIED') {
    return '该能力覆盖超出当前阶段允许范围。';
  }
  if (status === 404 || code === 'WORKBENCH_NOT_FOUND') {
    return 'Workbench 不存在或当前用户无权访问。';
  }
  return '阶段能力加载或保存失败，请稍后重试。';
}
