<template>
  <div v-loading="loading" class="stage-catalog-settings">
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

    <div class="catalog-layout">
      <el-card shadow="never" class="definition-list-card">
        <template #header>
          <div class="section-header">
            <strong>Stage Definitions</strong>
            <el-button type="primary" text @click="startCreate">新建</el-button>
          </div>
        </template>
        <div class="catalog-version">Catalog v{{ catalogVersion }}</div>
        <button
          v-for="definition in definitions"
          :key="definition.definitionIdentifier"
          type="button"
          class="definition-row"
          :class="{ selected: selectedIdentifier === definition.definitionIdentifier }"
          @click="selectDefinition(definition)"
        >
          <span>{{ effectiveSequence(definition) }}</span>
          <span class="definition-name">{{ effectiveName(definition) }}</span>
          <el-tag size="small" :type="lifecycleType(definition.lifecycleStatus)">
            {{ lifecycleLabel(definition.lifecycleStatus) }}
          </el-tag>
          <el-tag v-if="definition.hasDraft" size="small" type="warning">
            有草稿
          </el-tag>
        </button>
        <el-empty v-if="!definitions.length" description="暂无 Stage" />
      </el-card>

      <el-card shadow="never" class="definition-editor-card">
        <template #header>
          <div class="section-header">
            <strong>{{ createMode ? '新建 Stage 草稿' : '编辑 Stage 草稿' }}</strong>
            <span v-if="selectedDefinition" class="definition-version">
              Definition v{{ selectedDefinition.definitionVersion }} ·
              {{ selectedDefinition.lifecycleStatus }} ·
              hasDraft={{ selectedDefinition.hasDraft }}
            </span>
          </div>
        </template>

        <el-form label-position="top">
          <el-form-item label="Definition Identifier">
            <el-input
              v-model="definitionIdentifier"
              :disabled="!createMode"
              placeholder="例如 solution-design"
            />
          </el-form-item>
          <div class="two-columns">
            <el-form-item label="顺序">
              <el-input-number v-model="draft.sequenceNumber" :min="1" />
            </el-form-item>
            <el-form-item label="显示名称">
              <el-input v-model="draft.displayName" maxlength="120" />
            </el-form-item>
          </div>
          <el-form-item label="说明">
            <el-input
              v-model="draft.description"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 5 }"
            />
          </el-form-item>
          <el-form-item label="Stage Rules">
            <el-input
              v-model="draft.stageRules"
              type="textarea"
              :autosize="{ minRows: 7, maxRows: 18 }"
            />
          </el-form-item>
          <el-form-item label="允许的 Run Mode">
            <el-checkbox-group v-model="draft.allowedRunModes">
              <el-checkbox value="DISCUSS_READ_ONLY">只读讨论</el-checkbox>
              <el-checkbox value="MODIFY_WORKSPACE">修改工作区</el-checkbox>
            </el-checkbox-group>
          </el-form-item>

          <capability-selection
            title="Commands"
            kind="commands"
            :items="draft.commandReferences"
            :options="capabilityCatalog?.commands ?? []"
            @add="addReference('commands')"
            @remove="removeReference('commands', $event)"
            @select="selectReference('commands', $event.index, $event.key)"
          />
          <capability-selection
            title="Skills"
            kind="skills"
            required-enabled
            :items="draft.skillReferences"
            :options="capabilityCatalog?.skills ?? []"
            @add="addReference('skills')"
            @remove="removeReference('skills', $event)"
            @select="selectReference('skills', $event.index, $event.key)"
          />
          <capability-selection
            title="MCP Servers"
            kind="mcpServers"
            required-enabled
            :items="draft.mcpServerReferences"
            :options="capabilityCatalog?.mcpServers ?? []"
            @add="addReference('mcpServers')"
            @remove="removeReference('mcpServers', $event)"
            @select="selectReference('mcpServers', $event.index, $event.key)"
          />
        </el-form>

        <div class="actions">
          <el-button
            type="primary"
            data-test="stage-save-draft"
            :loading="saving"
            @click="saveDraft"
          >
            保存草稿
          </el-button>
          <el-button
            data-test="stage-publish"
            :loading="publishing"
            :disabled="createMode || !selectedDefinition?.hasDraft"
            @click="publishDraft"
          >
            发布
          </el-button>
          <el-button
            type="danger"
            plain
            data-test="stage-disable"
            :loading="disabling"
            :disabled="createMode || selectedDefinition?.lifecycleStatus !== 'PUBLISHED'"
            @click="disableStage"
          >
            停用
          </el-button>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onMounted, reactive, ref } from 'vue';
import { ElButton, ElCheckbox, ElSelect, ElOption } from 'element-plus';
import type {
  CapabilityDiscoveryItem,
  CapabilitySourceValidationResult,
} from '../api/capability-sources.js';
import { capabilitySelectionLabel } from '../../lib/capability-selection-label.js';
import {
  createStageDefinitionApiClient,
  type RequiredVersionSelectionInput,
  type StageDefinitionDraftInput,
  type StageDefinitionView,
  type VersionSelectionInput,
  type WorkbenchRunMode,
} from '../api/stage-definitions.js';

type ReferenceKind = 'commands' | 'skills' | 'mcpServers';
type MutableReference = VersionSelectionInput & { required?: boolean };
type MutableDraft = {
  sequenceNumber: number;
  displayName: string;
  description: string;
  stageRules: string;
  allowedRunModes: WorkbenchRunMode[];
  commandReferences: MutableReference[];
  skillReferences: MutableReference[];
  mcpServerReferences: MutableReference[];
};

const props = defineProps<{
  capabilityCatalog: CapabilitySourceValidationResult | null;
}>();

const CapabilitySelection = defineComponent({
  name: 'CapabilitySelection',
  props: {
    title: { type: String, required: true },
    kind: { type: String, required: true },
    items: { type: Array as () => MutableReference[], required: true },
    options: { type: Array as () => CapabilityDiscoveryItem[], required: true },
    requiredEnabled: { type: Boolean, default: false },
  },
  emits: ['add', 'remove', 'select'],
  setup(selectionProps, { emit }) {
    const keyOf = (item: VersionSelectionInput): string =>
      `${item.identifier}\u0000${item.version}`;
    return () => h('section', { class: 'capability-section' }, [
      h('div', { class: 'section-header' }, [
        h('strong', selectionProps.title),
        h(ElButton, { text: true, type: 'primary', onClick: () => emit('add') },
          () => '添加'),
      ]),
      ...selectionProps.items.map((item, index) => h('div', {
        class: 'capability-row',
        key: `${selectionProps.kind}-${index}`,
      }, [
        h(ElSelect, {
          modelValue: keyOf(item),
          filterable: true,
          placeholder: '从当前 Capability Source 选择精确版本',
          'onUpdate:modelValue': (key: string) => emit('select', { index, key }),
        }, () => selectionProps.options.map(option => h(ElOption, {
          key: keyOf(option),
          value: keyOf(option),
          label: capabilitySelectionLabel(
            selectionProps.kind as ReferenceKind, option,
          ),
        }))),
        selectionProps.requiredEnabled
          ? h(ElCheckbox, {
            modelValue: Boolean(item.required),
            'onUpdate:modelValue': (value: boolean) => { item.required = value; },
          }, () => '必需')
          : null,
        h(ElButton, {
          text: true,
          type: 'danger',
          onClick: () => emit('remove', index),
        }, () => '删除'),
      ])),
    ]);
  },
});

const api = createStageDefinitionApiClient();
const loading = ref(false);
const saving = ref(false);
const publishing = ref(false);
const disabling = ref(false);
const error = ref('');
const successMessage = ref('');
const catalogVersion = ref(1);
const definitions = ref<StageDefinitionView[]>([]);
const selectedIdentifier = ref('');
const definitionIdentifier = ref('');
const createMode = ref(true);
const draft = reactive<MutableDraft>(emptyDraft());

const capabilityCatalog = computed(() => props.capabilityCatalog);
const selectedDefinition = computed(() => definitions.value.find(
  definition => definition.definitionIdentifier === selectedIdentifier.value,
) ?? null);

onMounted(load);

async function load(keepIdentifier = ''): Promise<void> {
  loading.value = true;
  error.value = '';
  try {
    const catalog = await api.findAll();
    catalogVersion.value = catalog.stageCatalogVersion;
    definitions.value = catalog.definitions;
    const selected = catalog.definitions.find(
      definition => definition.definitionIdentifier === keepIdentifier,
    ) ?? catalog.definitions[0];
    if (selected) selectDefinition(selected);
    else startCreate();
  } catch (failure: unknown) {
    error.value = messageOf(failure, '加载 Stage Catalog 失败');
  } finally {
    loading.value = false;
  }
}

function startCreate(): void {
  createMode.value = true;
  selectedIdentifier.value = '';
  definitionIdentifier.value = '';
  assignDraft(emptyDraft());
}

function selectDefinition(definition: StageDefinitionView): void {
  createMode.value = false;
  selectedIdentifier.value = definition.definitionIdentifier;
  definitionIdentifier.value = definition.definitionIdentifier;
  assignDraft(definition.draft ?? definition.published ?? emptyDraft());
}

async function saveDraft(): Promise<void> {
  saving.value = true;
  error.value = '';
  successMessage.value = '';
  try {
    const identifier = definitionIdentifier.value.trim();
    if (!identifier) throw new Error('Definition Identifier 不能为空');
    if (createMode.value) {
      await api.create(identifier, draftInput(), catalogVersion.value);
    } else if (selectedDefinition.value) {
      await api.saveDraft(
        identifier, draftInput(), selectedDefinition.value.definitionVersion,
      );
    }
    successMessage.value = 'Stage 草稿已保存；线上 Published Revision 未变化';
    await load(identifier);
  } catch (failure: unknown) {
    error.value = messageOf(failure, 'Stage 草稿保存失败');
  } finally {
    saving.value = false;
  }
}

async function publishDraft(): Promise<void> {
  if (!selectedDefinition.value) return;
  publishing.value = true;
  error.value = '';
  try {
    await api.publish(
      selectedDefinition.value.definitionIdentifier,
      selectedDefinition.value.definitionVersion,
      catalogVersion.value,
    );
    successMessage.value = 'Stage Draft 已作为新的不可变 Revision 发布';
    await load(selectedDefinition.value.definitionIdentifier);
  } catch (failure: unknown) {
    error.value = messageOf(failure, 'Stage 发布失败');
  } finally {
    publishing.value = false;
  }
}

async function disableStage(): Promise<void> {
  if (!selectedDefinition.value) return;
  disabling.value = true;
  error.value = '';
  try {
    await api.disable(
      selectedDefinition.value.definitionIdentifier,
      selectedDefinition.value.definitionVersion,
      catalogVersion.value,
    );
    successMessage.value = 'Stage 已停用；已有 Workbench Snapshot 不受影响';
    await load(selectedDefinition.value.definitionIdentifier);
  } catch (failure: unknown) {
    error.value = messageOf(failure, 'Stage 停用失败');
  } finally {
    disabling.value = false;
  }
}

function draftInput(): StageDefinitionDraftInput {
  if (!draft.allowedRunModes.length) throw new Error('至少选择一个 Run Mode');
  return {
    sequenceNumber: draft.sequenceNumber,
    displayName: draft.displayName,
    description: draft.description,
    stageRules: draft.stageRules,
    allowedRunModes: [...draft.allowedRunModes],
    commandReferences: draft.commandReferences.map(reference => ({
      identifier: reference.identifier,
      version: reference.version,
    })),
    skillReferences: requiredReferences(draft.skillReferences),
    mcpServerReferences: requiredReferences(draft.mcpServerReferences),
  };
}

function requiredReferences(
  references: MutableReference[],
): RequiredVersionSelectionInput[] {
  return references.map(reference => ({
    identifier: reference.identifier,
    version: reference.version,
    required: Boolean(reference.required),
  }));
}

function addReference(kind: ReferenceKind): void {
  referenceList(kind).push({ identifier: '', version: '', required: false });
}

function removeReference(kind: ReferenceKind, index: number): void {
  referenceList(kind).splice(index, 1);
}

function selectReference(kind: ReferenceKind, index: number, key: string): void {
  const separator = key.indexOf('\u0000');
  if (separator < 0) return;
  const current = referenceList(kind)[index];
  referenceList(kind)[index] = {
    identifier: key.slice(0, separator),
    version: key.slice(separator + 1),
    required: current?.required ?? false,
  };
}

function referenceList(kind: ReferenceKind): MutableReference[] {
  if (kind === 'commands') return draft.commandReferences;
  if (kind === 'skills') return draft.skillReferences;
  return draft.mcpServerReferences;
}

function assignDraft(source: StageDefinitionDraftInput): void {
  draft.sequenceNumber = source.sequenceNumber;
  draft.displayName = source.displayName;
  draft.description = source.description;
  draft.stageRules = source.stageRules;
  draft.allowedRunModes = [...source.allowedRunModes];
  draft.commandReferences = source.commandReferences.map(item => ({ ...item }));
  draft.skillReferences = source.skillReferences.map(item => ({ ...item }));
  draft.mcpServerReferences = source.mcpServerReferences.map(item => ({ ...item }));
}

function emptyDraft(): MutableDraft {
  return {
    sequenceNumber: 10,
    displayName: '',
    description: '',
    stageRules: '',
    allowedRunModes: ['DISCUSS_READ_ONLY'],
    commandReferences: [],
    skillReferences: [],
    mcpServerReferences: [],
  };
}

function effectiveSequence(definition: StageDefinitionView): number {
  return definition.published?.sequenceNumber ?? definition.draft?.sequenceNumber ?? 0;
}

function effectiveName(definition: StageDefinitionView): string {
  return definition.published?.displayName ?? definition.draft?.displayName
    ?? definition.definitionIdentifier;
}

function lifecycleLabel(status: StageDefinitionView['lifecycleStatus']): string {
  return { DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已停用' }[status];
}

function lifecycleType(
  status: StageDefinitionView['lifecycleStatus'],
): '' | 'success' | 'info' {
  if (status === 'PUBLISHED') return 'success';
  if (status === 'DISABLED') return 'info';
  return '';
}

function messageOf(failure: unknown, fallback: string): string {
  return failure instanceof Error ? failure.message : fallback;
}
</script>

<style scoped>
.stage-catalog-settings { display: grid; gap: 14px; }
.catalog-layout { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 14px; }
.section-header, .actions { display: flex; align-items: center; gap: 10px; }
.section-header { justify-content: space-between; }
.catalog-version, .definition-version {
  color: var(--el-text-color-secondary); font-size: 12px;
}
.definition-row {
  width: 100%; display: flex; align-items: center; gap: 8px;
  border: 0; border-bottom: 1px solid var(--el-border-color-lighter);
  background: transparent; padding: 12px 6px; text-align: left; cursor: pointer;
}
.definition-row.selected { background: var(--el-fill-color-light); }
.definition-name { flex: 1; font-weight: 600; }
.two-columns { display: grid; grid-template-columns: 180px 1fr; gap: 12px; }
.capability-section {
  margin-top: 14px; padding: 12px; border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
:deep(.capability-row) {
  display: grid; grid-template-columns: minmax(240px, 1fr) auto auto;
  align-items: center; gap: 10px; margin-top: 10px;
}
.actions { justify-content: flex-end; margin-top: 18px; }
@media (max-width: 1000px) {
  .catalog-layout { grid-template-columns: 1fr; }
  .two-columns { grid-template-columns: 1fr; }
}
</style>
