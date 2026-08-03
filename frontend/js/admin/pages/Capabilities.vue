<template>
  <admin-shell active="capabilities" @ready="loadInitial">
    <template #header-actions>
      <el-button text :loading="loading" @click="loadInitial">刷新</el-button>
    </template>

    <div class="view-wrap admin-capability-view">
      <el-alert
        v-if="error"
        class="admin-capability-alert"
        type="error"
        :closable="false"
        :title="error"
        show-icon
      />

      <el-alert
        v-if="successMessage"
        class="admin-capability-alert"
        type="success"
        :closable="false"
        :title="successMessage"
        show-icon
      />

      <div v-loading="loading" class="admin-capability-body">
        <el-tabs v-model="activePhase" class="admin-capability-tabs">
          <el-tab-pane
            v-for="phase in phaseOrder"
            :key="phase"
            :label="phaseLabel(phase)"
            :name="phase"
          />
        </el-tabs>

        <template v-if="currentProfile">
          <el-card shadow="never" class="admin-capability-profile-card">
            <template #header>
              <div class="admin-capability-card-header">
                <div>
                  <span class="admin-capability-card-title">
                    {{ phaseLabel(currentProfile.phase) }}
                  </span>
                  <span class="admin-capability-card-subtitle">
                    {{ currentProfile.profileId }} · v{{ currentProfile.profileVersion }}
                  </span>
                </div>
                <div class="admin-capability-profile-meta">
                  <el-tag size="small" effect="plain">
                    更新者 {{ currentProfile.updatedByName }}
                  </el-tag>
                  <el-tag size="small" effect="plain">
                    {{ fmtTime(currentProfile.updatedAt) }}
                  </el-tag>
                  <code :title="currentProfile.profileHash">
                    {{ shortHash(currentProfile.profileHash) }}
                  </code>
                </div>
              </div>
            </template>

            <el-alert
              type="info"
              :closable="false"
              show-icon
              title="保存后 Profile 版本自增，已有 Workbench 的高级覆盖将自动重置为默认。"
            />

            <section class="admin-capability-section">
              <div class="admin-capability-section-heading">
                <h3>Rules</h3>
                <span>平台安全规则，建议保持 required</span>
              </div>
              <div class="admin-capability-item-list">
                <div
                  v-for="(item, index) in ruleItems"
                  :key="'rule-' + item.id"
                  class="admin-capability-item"
                >
                  <el-input
                    :model-value="item.id"
                    size="small"
                    placeholder="rule-id"
                    :disabled="saving"
                    @update:model-value="updateCapability(index, 'id', $event)"
                  />
                  <el-tag size="small" :type="item.required ? 'danger' : 'info'">
                    {{ item.required ? 'required' : 'optional' }}
                  </el-tag>
                  <el-switch
                    :model-value="item.required"
                    :disabled="saving"
                    active-text="必选"
                    inactive-text="可选"
                    @update:model-value="updateCapability(index, 'required', $event)"
                  />
                  <el-button
                    text
                    type="danger"
                    size="small"
                    :disabled="saving"
                    @click="removeCapability(index)"
                  >
                    移除
                  </el-button>
                </div>
                <el-button
                  text
                  type="primary"
                  size="small"
                  :disabled="saving"
                  @click="addCapability('RULE')"
                >
                  + 添加 Rule
                </el-button>
              </div>
            </section>

            <section class="admin-capability-section">
              <div class="admin-capability-section-heading">
                <h3>Skills</h3>
                <span>从 Catalog 选择 Skill 分配到本阶段</span>
              </div>
              <div class="admin-capability-item-list">
                <div
                  v-for="(item, index) in skillItems"
                  :key="'skill-' + item.id"
                  class="admin-capability-item"
                >
                  <el-select
                    :model-value="item.id"
                    size="small"
                    filterable
                    placeholder="选择 Skill"
                    :disabled="saving"
                    class="admin-capability-id-select"
                    @update:model-value="updateCapability(skillStart + index, 'id', $event)"
                  >
                    <el-option
                      v-for="skill in catalog?.skills ?? []"
                      :key="skill.id"
                      :label="`${skill.id} (${skill.description})`"
                      :value="skill.id"
                    />
                  </el-select>
                  <el-tag size="small" :type="item.required ? 'danger' : 'info'">
                    {{ item.required ? 'required' : 'optional' }}
                  </el-tag>
                  <el-switch
                    :model-value="item.required"
                    :disabled="saving"
                    active-text="必选"
                    inactive-text="可选"
                    @update:model-value="updateCapability(skillStart + index, 'required', $event)"
                  />
                  <el-button
                    text
                    type="danger"
                    size="small"
                    :disabled="saving"
                    @click="removeCapability(skillStart + index)"
                  >
                    移除
                  </el-button>
                </div>
                <el-button
                  text
                  type="primary"
                  size="small"
                  :disabled="saving || !catalog?.skills.length"
                  @click="addCapability('SKILL')"
                >
                  + 添加 Skill
                </el-button>
              </div>
            </section>

            <section class="admin-capability-section">
              <div class="admin-capability-section-heading">
                <h3>MCP Servers</h3>
                <span>从 Catalog 选择 MCP Server 分配到本阶段</span>
              </div>
              <div class="admin-capability-item-list">
                <div
                  v-for="(item, index) in mcpItems"
                  :key="'mcp-' + item.id"
                  class="admin-capability-item"
                >
                  <el-select
                    :model-value="item.id"
                    size="small"
                    filterable
                    placeholder="选择 MCP Server"
                    :disabled="saving"
                    class="admin-capability-id-select"
                    @update:model-value="updateCapability(mcpStart + index, 'id', $event)"
                  >
                    <el-option
                      v-for="mcp in catalog?.mcpServers ?? []"
                      :key="mcp.id"
                      :label="`${mcp.id} (${mcp.description})`"
                      :value="mcp.id"
                    />
                  </el-select>
                  <el-tag size="small" :type="item.required ? 'danger' : 'info'">
                    {{ item.required ? 'required' : 'optional' }}
                  </el-tag>
                  <el-switch
                    :model-value="item.required"
                    :disabled="saving"
                    active-text="必选"
                    inactive-text="可选"
                    @update:model-value="updateCapability(mcpStart + index, 'required', $event)"
                  />
                  <el-button
                    text
                    type="danger"
                    size="small"
                    :disabled="saving"
                    @click="removeCapability(mcpStart + index)"
                  >
                    移除
                  </el-button>
                </div>
                <el-button
                  text
                  type="primary"
                  size="small"
                  :disabled="saving || !catalog?.mcpServers.length"
                  @click="addCapability('MCP_SERVER')"
                >
                  + 添加 MCP Server
                </el-button>
              </div>
            </section>

            <div class="admin-capability-actions">
              <el-button
                type="primary"
                :loading="saving"
                :disabled="!dirty"
                data-test="capability-save"
                @click="save"
              >
                保存
              </el-button>
              <el-button
                :disabled="!dirty || saving"
                @click="resetDraft"
              >
                重置
              </el-button>
            </div>
          </el-card>
        </template>

        <el-empty
          v-else-if="!loading"
          description="暂无 Profile 数据"
        />
      </div>
    </div>
  </admin-shell>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import {
  fetchProfiles,
  fetchCatalog,
  updateProfile,
  type AdminPhaseCapabilityProfile,
  type AdminCapabilityCatalog,
  type CapabilityReferenceInput,
} from '../api/capabilities.js';

type Phase = 'REQUIREMENT_ANALYSIS' | 'SOLUTION_DESIGN' | 'IMPLEMENT_TEST' | 'REVIEW_REFACTOR';

const phaseOrder: Phase[] = [
  'REQUIREMENT_ANALYSIS',
  'SOLUTION_DESIGN',
  'IMPLEMENT_TEST',
  'REVIEW_REFACTOR',
];

const loading = ref(false);
const saving = ref(false);
const error = ref('');
const successMessage = ref('');
const activePhase = ref<Phase>('REQUIREMENT_ANALYSIS');
const profiles = ref<AdminPhaseCapabilityProfile[]>([]);
const catalog = ref<AdminCapabilityCatalog | null>(null);
const draft = ref<CapabilityReferenceInput[]>([]);

const currentProfile = computed<AdminPhaseCapabilityProfile | null>(() => {
  return profiles.value.find(p => p.phase === activePhase.value) ?? null;
});

const ruleItems = computed(() =>
  draft.value.filter(c => c.type === 'RULE'),
);
const skillItems = computed(() =>
  draft.value.filter(c => c.type === 'SKILL'),
);
const mcpItems = computed(() =>
  draft.value.filter(c => c.type === 'MCP_SERVER'),
);

const skillStart = computed(() =>
  draft.value.findIndex(c => c.type === 'SKILL'),
);
const mcpStart = computed(() =>
  draft.value.findIndex(c => c.type === 'MCP_SERVER'),
);

const dirty = computed(() => {
  if (!currentProfile.value) return false;
  const original = currentProfile.value.capabilities;
  if (original.length !== draft.value.length) return true;
  const sortFn = (a: { id: string; type: string; required: boolean }, b: { id: string; type: string; required: boolean }) =>
    a.type < b.type ? -1 : a.type > b.type ? 1 : a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
  const sortedOriginal = [...original].sort(sortFn);
  const sortedDraft = [...draft.value].sort(sortFn);
  return sortedOriginal.some((o, i) =>
    o.id !== sortedDraft[i].id ||
    o.type !== sortedDraft[i].type ||
    o.required !== sortedDraft[i].required,
  );
});

watch(activePhase, () => {
  resetDraft();
});

async function loadInitial() {
  loading.value = true;
  error.value = '';
  successMessage.value = '';
  try {
    const [profileList, catalogData] = await Promise.all([
      fetchProfiles(),
      fetchCatalog(),
    ]);
    profiles.value = profileList;
    catalog.value = catalogData;
    resetDraft();
  } catch (e: any) {
    error.value = e?.message ?? '加载失败';
  } finally {
    loading.value = false;
  }
}

function resetDraft() {
  if (!currentProfile.value) {
    draft.value = [];
    return;
  }
  draft.value = currentProfile.value.capabilities.map(c => ({
    id: c.id,
    type: c.type as 'RULE' | 'SKILL' | 'MCP_SERVER',
    required: c.required,
  }));
}

function addCapability(type: 'RULE' | 'SKILL' | 'MCP_SERVER') {
  draft.value.push({ id: '', type, required: false });
}

function removeCapability(index: number) {
  draft.value.splice(index, 1);
}

function updateCapability(
  index: number,
  field: 'id' | 'required',
  value: string | boolean,
) {
  if (field === 'id') {
    draft.value[index] = { ...draft.value[index], id: value as string };
  } else {
    draft.value[index] = { ...draft.value[index], required: value as boolean };
  }
}

async function save() {
  if (!currentProfile.value) return;
  const invalid = draft.value.find(c => !c.id.trim());
  if (invalid) {
    error.value = '存在未填写 ID 的能力项';
    return;
  }
  saving.value = true;
  error.value = '';
  successMessage.value = '';
  try {
    const updated = await updateProfile(
      currentProfile.value.phase,
      draft.value,
      currentProfile.value.version,
    );
    const idx = profiles.value.findIndex(
      p => p.phase === updated.phase,
    );
    if (idx >= 0) {
      profiles.value[idx] = updated;
    }
    resetDraft();
    successMessage.value = `${phaseLabel(updated.phase)} 已更新到 v${updated.profileVersion}`;
  } catch (e: any) {
    error.value = e?.message ?? '保存失败';
  } finally {
    saving.value = false;
  }
}

function phaseLabel(phase: string): string {
  const labels: Record<string, string> = {
    REQUIREMENT_ANALYSIS: '需求分析',
    SOLUTION_DESIGN: '方案设计',
    IMPLEMENT_TEST: '开发测试',
    REVIEW_REFACTOR: 'Review 重构',
  };
  return labels[phase] ?? phase;
}

function fmtTime(value: number): string {
  return new Date(value).toLocaleString('zh-CN');
}

function shortHash(value: string): string {
  return value.length > 16
    ? `${value.slice(0, 12)}…${value.slice(-4)}`
    : value;
}
</script>

<style scoped>
.admin-capability-view {
  max-width: 900px;
}

.admin-capability-alert {
  margin-bottom: 12px;
}

.admin-capability-body {
  min-height: 300px;
}

.admin-capability-tabs {
  margin-bottom: 16px;
}

.admin-capability-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.admin-capability-card-title {
  font-weight: 700;
}

.admin-capability-card-subtitle {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.admin-capability-profile-meta {
  display: flex;
  align-items: center;
  gap: 6px;
}

.admin-capability-profile-meta code {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.admin-capability-section {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 14px;
  margin-top: 16px;
}

.admin-capability-section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.admin-capability-section-heading h3 {
  margin: 0;
}

.admin-capability-section-heading span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.admin-capability-item-list {
  display: grid;
  gap: 10px;
}

.admin-capability-item {
  display: flex;
  align-items: center;
  gap: 10px;
}

.admin-capability-id-select {
  flex: 1;
  min-width: 200px;
}

.admin-capability-actions {
  margin-top: 20px;
  display: flex;
  gap: 10px;
}
</style>
