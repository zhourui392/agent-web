<template>
  <el-drawer
    :model-value="visible"
    class="workbench-capability-drawer"
    direction="rtl"
    size="min(620px, 100vw)"
    destroy-on-close
    @update:model-value="emit('update:visible', Boolean($event))"
  >
    <template #header>
      <div class="capability-drawer-heading">
        <div>
          <span class="capability-kicker">阶段高级设置</span>
          <h2>Rules、Skills 与 MCP</h2>
        </div>
        <el-button
          text
          :loading="loading"
          data-test="capability-refresh"
          @click="emit('refresh')"
        >
          刷新
        </el-button>
      </div>
    </template>

    <div v-loading="loading" class="capability-drawer-body">
      <el-alert
        v-if="error"
        type="error"
        show-icon
        :closable="false"
        :title="error"
        data-test="capability-error"
      />
      <el-alert
        v-if="notice"
        type="success"
        show-icon
        :closable="false"
        :title="notice"
        data-test="capability-next-run-notice"
      />

      <template v-if="profile">
        <section class="capability-profile-summary">
          <div>
            <span>Effective Profile</span>
            <strong>{{ profile.profileId }}</strong>
          </div>
          <div class="capability-profile-tags">
            <el-tag :type="profile.status === 'AVAILABLE' ? 'success' : 'warning'">
              {{ profile.status }}
            </el-tag>
            <el-tag effect="plain">v{{ profile.profileVersion }}</el-tag>
          </div>
          <code :title="profile.profileHash">{{ shortHash(profile.profileHash) }}</code>
        </section>

        <el-alert
          type="info"
          show-icon
          :closable="false"
          title="覆盖配置允许在运行中保存，但只影响下一轮；当前 Run Snapshot 不会被原地修改。"
        />

        <section v-if="profile.warnings.length" class="capability-section">
          <h3>降级与提示</h3>
          <ul class="capability-warning-list">
            <li v-for="warning in profile.warnings" :key="warning">{{ warning }}</li>
          </ul>
        </section>

        <section class="capability-section" data-test="capability-rules">
          <div class="capability-section-heading">
            <h3>Rules</h3>
            <span>平台安全规则不可关闭</span>
          </div>
          <div class="capability-item-list">
            <div v-for="rule in profile.rules" :key="rule.id" class="capability-item">
              <div>
                <strong>{{ rule.id }}</strong>
                <small>{{ rule.summary || rule.source }}</small>
              </div>
              <div class="capability-item-tags">
                <el-tag size="small" effect="plain">{{ rule.source }}</el-tag>
                <el-tag size="small" :type="rule.required ? 'danger' : 'info'">
                  {{ rule.required ? 'required' : 'optional' }}
                </el-tag>
              </div>
            </div>
          </div>
        </section>

        <section class="capability-section" data-test="capability-skills">
          <div class="capability-section-heading">
            <h3>Skills</h3>
            <span>只可选择 Profile 允许的 optional Skill</span>
          </div>
          <div v-if="requiredSkills.length" class="capability-required-list">
            <el-checkbox
              v-for="skill in requiredSkills"
              :key="skill.id"
              :model-value="true"
              disabled
            >
              {{ skill.id }}（required）
            </el-checkbox>
          </div>
          <el-checkbox-group
            :model-value="draft.optionalSkillIds"
            :disabled="readOnly || saving"
            @update:model-value="updateSkillIds"
          >
            <el-checkbox
              v-for="skill in optionalSkills"
              :key="skill.id"
              :value="skill.id"
            >
              <span>{{ skill.id }}</span>
              <small v-if="skill.summary">{{ skill.summary }}</small>
            </el-checkbox>
          </el-checkbox-group>
        </section>

        <section class="capability-section" data-test="capability-mcp-servers">
          <div class="capability-section-heading">
            <h3>MCP Servers</h3>
            <span>权限仍与阶段、Runtime 和 Workspace Policy 求交</span>
          </div>
          <div v-if="requiredMcpServers.length" class="capability-required-list">
            <el-checkbox
              v-for="server in requiredMcpServers"
              :key="server.id"
              :model-value="true"
              disabled
            >
              <span class="capability-mcp-copy">
                <span>{{ server.id }}（required）</span>
                <small v-if="server.summary">{{ server.summary }}</small>
              </span>
              <el-tag
                size="small"
                data-test="capability-mcp-access"
                :type="mcpAccessType(server.access)"
                :effect="server.access === 'WRITE' ? 'dark' : 'plain'"
              >
                {{ mcpAccessLabel(server) }}
              </el-tag>
            </el-checkbox>
          </div>
          <el-checkbox-group
            :model-value="draft.optionalMcpServerIds"
            :disabled="readOnly || saving"
            @update:model-value="updateMcpServerIds"
          >
            <el-checkbox
              v-for="server in optionalMcpServers"
              :key="server.id"
              :value="server.id"
              :disabled="server.source === 'UNAVAILABLE'
                && !draft.optionalMcpServerIds.includes(server.id)"
            >
              <span class="capability-mcp-copy">
                <span>{{ server.id }}</span>
                <small v-if="server.summary">{{ server.summary }}</small>
              </span>
              <el-tag
                size="small"
                data-test="capability-mcp-access"
                :type="mcpAccessType(server.access)"
                :effect="server.access === 'WRITE' ? 'dark' : 'plain'"
              >
                {{ mcpAccessLabel(server) }}
              </el-tag>
            </el-checkbox>
          </el-checkbox-group>
        </section>

        <section class="capability-section" data-test="capability-additional-rule">
          <div class="capability-section-heading">
            <h3>Additional Rule</h3>
            <span>仅追加非安全偏好，最多 4000 字符</span>
          </div>
          <el-input
            type="textarea"
            :rows="5"
            :maxlength="4000"
            show-word-limit
            resize="vertical"
            :disabled="readOnly || saving"
            :model-value="draft.additionalRule"
            placeholder="例如：优先复用现有组件，并在修改后运行聚焦测试。"
            @update:model-value="updateAdditionalRule"
          />
        </section>

        <section v-if="profile.activeRunSnapshotHash" class="capability-active-binding">
          <span>当前活动 Run Binding</span>
          <code :title="profile.activeRunSnapshotHash">
            {{ shortHash(profile.activeRunSnapshotHash) }}
          </code>
          <small>保存不会改变该 Hash；下一轮会重新解析并生成新快照。</small>
        </section>
      </template>

      <el-empty
        v-else-if="!loading && !error"
        description="当前阶段没有可展示的 Capability Profile"
      />
    </div>

    <template #footer>
      <div class="capability-drawer-footer">
        <el-button @click="emit('update:visible', false)">关闭</el-button>
        <span class="capability-footer-spacer"></span>
        <el-button
          :disabled="readOnly || !canRestoreDefaults || saving"
          data-test="capability-restore-defaults"
          @click="emit('restore-defaults')"
        >
          恢复默认
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="readOnly || loading || !profile || !dirty"
          data-test="capability-save"
          @click="emit('save')"
        >
          保存并用于下一轮
        </el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
  visible: { type: Boolean, required: true },
  profile: { type: Object, default: null },
  override: { type: Object, default: null },
  draft: { type: Object, required: true },
  loading: { type: Boolean, required: true },
  saving: { type: Boolean, required: true },
  error: { type: String, default: null },
  notice: { type: String, default: null },
  dirty: { type: Boolean, required: true },
  canRestoreDefaults: { type: Boolean, required: true },
  readOnly: { type: Boolean, default: false },
});

const emit = defineEmits([
  'update:visible',
  'update:draft',
  'refresh',
  'save',
  'restore-defaults',
]);

const requiredSkills = computed(() => required(props.profile?.skills));
const optionalSkills = computed(() => optional(props.profile?.skills));
const requiredMcpServers = computed(() => required(props.profile?.mcpServers));
const optionalMcpServers = computed(() => optional(props.profile?.mcpServers));

function required(items) {
  return (items || []).filter(item => item.required);
}

function optional(items) {
  return (items || []).filter(item => !item.required);
}

function mcpAccessType(access) {
  return access === 'WRITE' ? 'danger' : access === 'READ' ? 'info' : 'warning';
}

function mcpAccessLabel(server) {
  if (server.access === 'WRITE') return 'WRITE · 可修改外部状态';
  if (server.access === 'READ') return 'READ · 只读访问';
  return server.source === 'UNAVAILABLE'
    ? '不可用 · 未授权' : '未授权';
}

function updateSkillIds(value) {
  if (!Array.isArray(value) || !value.every(item => typeof item === 'string')) return;
  updateDraft({ optionalSkillIds: [...value] });
}

function updateMcpServerIds(value) {
  if (!Array.isArray(value) || !value.every(item => typeof item === 'string')) return;
  updateDraft({ optionalMcpServerIds: [...value] });
}

function updateAdditionalRule(value) {
  if (typeof value !== 'string') return;
  updateDraft({ additionalRule: value });
}

function updateDraft(change) {
  emit('update:draft', {
    optionalSkillIds: [...(change.optionalSkillIds ?? props.draft.optionalSkillIds)],
    optionalMcpServerIds: [...(
      change.optionalMcpServerIds ?? props.draft.optionalMcpServerIds
    )],
    additionalRule: change.additionalRule ?? props.draft.additionalRule,
  });
}

function shortHash(value) {
  return value.length > 16 ? `${value.slice(0, 12)}…${value.slice(-4)}` : value;
}
</script>

<style scoped>
.capability-drawer-heading,
.capability-section-heading,
.capability-drawer-footer,
.capability-profile-summary,
.capability-item,
.capability-active-binding {
  display: flex;
  align-items: center;
  gap: 12px;
}

.capability-drawer-heading,
.capability-section-heading,
.capability-profile-summary,
.capability-item {
  justify-content: space-between;
}

.capability-drawer-heading h2,
.capability-section h3 {
  margin: 0;
}

.capability-kicker,
.capability-section-heading span,
.capability-active-binding small,
.capability-item small {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.capability-drawer-body {
  display: grid;
  gap: 16px;
  min-height: 240px;
}

.capability-profile-summary,
.capability-section,
.capability-active-binding {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 14px;
}

.capability-profile-summary > div:first-child,
.capability-item > div:first-child,
.capability-active-binding {
  display: grid;
  gap: 4px;
}

.capability-profile-tags,
.capability-item-tags {
  display: flex;
  gap: 6px;
}

.capability-section {
  display: grid;
  gap: 12px;
}

.capability-item-list,
.capability-required-list,
.el-checkbox-group {
  display: grid;
  gap: 10px;
}

.el-checkbox small {
  display: block;
  margin-left: 4px;
  color: var(--el-text-color-secondary);
}

.capability-mcp-copy {
  display: inline-grid;
  gap: 2px;
  margin-right: 8px;
  vertical-align: middle;
}

.capability-warning-list {
  margin: 0;
  padding-left: 20px;
}

.capability-active-binding {
  align-items: flex-start;
}

.capability-footer-spacer {
  flex: 1;
}

code {
  overflow-wrap: anywhere;
}
</style>
