<template>
  <div v-loading="loading" class="capability-source-settings">
    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      :title="error"
      show-icon
    />
    <el-alert
      v-if="successMessage"
      type="success"
      :closable="false"
      :title="successMessage"
      show-icon
    />
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="MCP 环境变量只能配置 Secret Reference；不得在 JSON 中填写 Secret 明文。"
    />

    <el-card shadow="never">
      <template #header>
        <div class="section-header">
          <strong>Commands 目录</strong>
          <el-button text type="primary" @click="addCommandDirectory">
            添加目录
          </el-button>
        </div>
      </template>
      <div data-test="command-directory-list" class="directory-list">
        <div
          v-for="(directory, index) in form.commandCatalogDirectories"
          :key="`command-${index}`"
          class="directory-row"
        >
          <el-input
            v-model="directory.directoryIdentifier"
            placeholder="目录标识，例如 platform-commands"
          />
          <el-input
            v-model="directory.absoluteDirectory"
            placeholder="服务端绝对目录"
          />
          <el-switch v-model="directory.enabled" active-text="启用" />
          <el-button text type="danger" @click="removeCommandDirectory(index)">
            删除
          </el-button>
        </div>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="section-header">
          <strong>Skills 目录</strong>
          <el-button text type="primary" @click="addSkillDirectory">
            添加目录
          </el-button>
        </div>
      </template>
      <div data-test="skill-directory-list" class="directory-list">
        <div
          v-for="(directory, index) in form.skillCatalogDirectories"
          :key="`skill-${index}`"
          class="directory-row skill-row"
        >
          <el-input
            v-model="directory.directoryIdentifier"
            placeholder="目录标识，例如 approved-skills"
          />
          <el-input
            v-model="directory.absoluteDirectory"
            placeholder="服务端 Skill 根目录（递归识别 SKILL.md）"
          />
          <el-select v-model="directory.trustSource" aria-label="可信来源">
            <el-option label="Platform" value="PLATFORM" />
            <el-option label="Administrator" value="APPROVED_USER" />
          </el-select>
          <el-switch v-model="directory.enabled" active-text="启用" />
          <el-button text type="danger" @click="removeSkillDirectory(index)">
            删除
          </el-button>
        </div>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header><strong>MCP JSON</strong></template>
      <el-input
        v-model="mcpJson"
        data-test="mcp-json-editor"
        type="textarea"
        :autosize="{ minRows: 12, maxRows: 28 }"
        spellcheck="false"
      />
    </el-card>

    <el-card v-if="validationResult" shadow="never">
      <template #header><strong>验证与发现结果</strong></template>
      <div class="discovery-summary">
        <el-tag>Commands {{ validationResult.commands?.length ?? 0 }}</el-tag>
        <el-tag>Skills {{ validationResult.skills?.length ?? 0 }}</el-tag>
        <el-tag>MCP {{ validationResult.mcpServers?.length ?? 0 }}</el-tag>
      </div>
      <pre>{{ validationText }}</pre>
    </el-card>

    <div class="actions">
      <el-button
        data-test="capability-source-validate"
        :loading="validating"
        @click="validateSources"
      >
        验证并预览
      </el-button>
      <el-button
        type="primary"
        data-test="capability-source-save"
        :loading="saving"
        @click="saveSources"
      >
        原子保存
      </el-button>
      <span class="version">配置版本 {{ configurationVersion }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import {
  createCapabilitySourceApiClient,
  type CapabilitySourceCandidate,
  type CapabilitySourceValidationResult,
  type CommandCatalogDirectoryInput,
  type McpCatalogConfiguration,
  type SkillCatalogDirectoryInput,
} from '../api/capability-sources.js';

const emit = defineEmits<{
  catalogChanged: [result: CapabilitySourceValidationResult];
}>();

const api = createCapabilitySourceApiClient();
const loading = ref(false);
const validating = ref(false);
const saving = ref(false);
const error = ref('');
const successMessage = ref('');
const configurationVersion = ref(0);
const validationResult = ref<CapabilitySourceValidationResult | null>(null);
const mcpJson = ref(defaultMcpJson());
const form = reactive<{
  commandCatalogDirectories: CommandCatalogDirectoryInput[];
  skillCatalogDirectories: SkillCatalogDirectoryInput[];
}>({
  commandCatalogDirectories: [],
  skillCatalogDirectories: [],
});

const validationText = computed(() => JSON.stringify({
  commands: validationResult.value?.commands ?? [],
  skills: validationResult.value?.skills ?? [],
  mcpServers: validationResult.value?.mcpServers ?? [],
  warnings: validationResult.value?.warnings ?? [],
}, null, 2));

onMounted(load);

async function load(): Promise<void> {
  loading.value = true;
  error.value = '';
  try {
    const configuration = await api.find();
    configurationVersion.value = configuration.version;
    form.commandCatalogDirectories.splice(
      0, form.commandCatalogDirectories.length,
      ...configuration.commandCatalogDirectories.map(directory => ({ ...directory })),
    );
    form.skillCatalogDirectories.splice(
      0, form.skillCatalogDirectories.length,
      ...configuration.skillCatalogDirectories.map(directory => ({ ...directory })),
    );
    mcpJson.value = JSON.stringify(configuration.mcpConfiguration, null, 2);
    const result = await api.validate(candidate());
    setValidationResult(result);
  } catch (failure: unknown) {
    error.value = messageOf(failure, '加载 Capability Source 失败');
  } finally {
    loading.value = false;
  }
}

async function validateSources(): Promise<void> {
  validating.value = true;
  error.value = '';
  successMessage.value = '';
  try {
    const result = await api.validate(candidate());
    setValidationResult(result);
    successMessage.value = '验证通过，尚未保存';
  } catch (failure: unknown) {
    error.value = messageOf(failure, 'Capability Source 验证失败');
  } finally {
    validating.value = false;
  }
}

async function saveSources(): Promise<void> {
  saving.value = true;
  error.value = '';
  successMessage.value = '';
  try {
    const updated = await api.update(candidate(), configurationVersion.value);
    configurationVersion.value = updated.version;
    const result = await api.validate(candidate());
    setValidationResult(result);
    successMessage.value = `Capability Source 已保存为版本 ${updated.version}`;
  } catch (failure: unknown) {
    error.value = messageOf(failure, 'Capability Source 保存失败');
  } finally {
    saving.value = false;
  }
}

function candidate(): CapabilitySourceCandidate {
  return {
    commandCatalogDirectories: form.commandCatalogDirectories.map(
      directory => ({ ...directory }),
    ),
    skillCatalogDirectories: form.skillCatalogDirectories.map(
      directory => ({ ...directory }),
    ),
    mcpConfiguration: parseMcpConfiguration(),
  };
}

function parseMcpConfiguration(): McpCatalogConfiguration {
  const parsed = JSON.parse(mcpJson.value) as Partial<McpCatalogConfiguration>;
  if (!parsed || typeof parsed !== 'object'
      || typeof parsed.schema !== 'string' || !Array.isArray(parsed.servers)) {
    throw new Error('MCP JSON 必须包含 schema 和 servers 数组');
  }
  return parsed as McpCatalogConfiguration;
}

function setValidationResult(result: CapabilitySourceValidationResult): void {
  validationResult.value = result;
  emit('catalogChanged', result);
}

function addCommandDirectory(): void {
  form.commandCatalogDirectories.push({
    directoryIdentifier: '', absoluteDirectory: '', enabled: true,
  });
}

function removeCommandDirectory(index: number): void {
  form.commandCatalogDirectories.splice(index, 1);
}

function addSkillDirectory(): void {
  form.skillCatalogDirectories.push({
    directoryIdentifier: '', absoluteDirectory: '',
    trustSource: 'APPROVED_USER', enabled: true,
  });
}

function removeSkillDirectory(index: number): void {
  form.skillCatalogDirectories.splice(index, 1);
}

function defaultMcpJson(): string {
  return JSON.stringify({
    schema: 'workbench-mcp-catalog@1',
    servers: [],
  }, null, 2);
}

function messageOf(failure: unknown, fallback: string): string {
  return failure instanceof Error ? failure.message : fallback;
}
</script>

<style scoped>
.capability-source-settings { display: grid; gap: 14px; }
.section-header, .actions, .discovery-summary {
  display: flex; align-items: center; gap: 10px;
}
.section-header { justify-content: space-between; }
.directory-list { display: grid; gap: 10px; }
.directory-row {
  display: grid;
  grid-template-columns: minmax(150px, .7fr) minmax(260px, 1.5fr) auto auto;
  gap: 10px;
  align-items: center;
}
.skill-row {
  grid-template-columns: minmax(130px, .6fr) minmax(240px, 1.3fr) 150px auto auto;
}
.actions { justify-content: flex-end; }
.version { color: var(--el-text-color-secondary); font-size: 12px; }
pre { white-space: pre-wrap; word-break: break-word; max-height: 320px; overflow: auto; }
@media (max-width: 900px) {
  .directory-row, .skill-row { grid-template-columns: 1fr; }
}
</style>
