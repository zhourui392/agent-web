<template>
  <admin-shell active="modes" @ready="load">
    <template #header-actions>
      <el-button text :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" @click="openCreate">
        <el-icon><plus /></el-icon>新建模式
      </el-button>
    </template>

    <div class="view-wrap">
      <el-tabs v-model="activeTab" type="border-card">
        <el-tab-pane label="模式" name="modes">
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="模式 = 一组可绑定会话的能力配置（命令 / Skills / MCP / 默认提示词 / 权限），仅 Claude 会话支持；管理员可维护所有用户的模式。"
            style="margin-bottom: 16px" />

          <el-table v-loading="loading" :data="modes" empty-text="还没有任何模式">
            <el-table-column prop="displayName" label="显示名称" min-width="160"></el-table-column>
            <el-table-column prop="identifier" label="标识符" min-width="180">
              <template #default="scope">
                <span class="mono-text">{{ scope.row.identifier }}</span>
              </template>
            </el-table-column>
            <el-table-column label="归属者" min-width="140">
              <template #default="scope">
                {{ scope.row.ownerUsername || scope.row.ownerUserId }}
              </template>
            </el-table-column>
            <el-table-column label="能力" min-width="180">
              <template #default="scope">
                命令 {{ scope.row.commands.length }} · Skills {{ scope.row.skills.length }}
                · MCP {{ scope.row.mcpServers.length }}
              </template>
            </el-table-column>
            <el-table-column prop="permissionMode" label="权限模式" width="140">
              <template #default="scope">{{ scope.row.permissionMode || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="scope">
                <el-button size="small" @click="openEdit(scope.row)">编辑</el-button>
                <el-button size="small" type="danger" plain @click="removeMode(scope.row)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <h3 class="section-title">模板库</h3>
          <el-empty v-if="templates.length === 0" description="暂无可用模板" :image-size="60" />
          <div v-else class="card-grid">
            <el-card v-for="template in templates" :key="template.revisionId" shadow="hover">
              <div class="card-title">{{ template.displayName }}</div>
              <div class="card-desc">{{ template.description }}</div>
              <div class="card-meta">
                命令 {{ template.commandCount }} · Skills {{ template.skillCount }}
                · MCP {{ template.mcpServerCount }}
              </div>
              <div class="card-actions">
                <el-button size="small" type="primary" plain @click="forkTemplate(template)">
                  复制为我的模式
                </el-button>
              </div>
            </el-card>
          </div>
        </el-tab-pane>

        <el-tab-pane label="能力目录" name="capabilities">
          <el-alert
            type="info"
            :closable="false"
            show-icon
            style="margin-bottom: 16px">
            <template #title>
              目录由扫描来源发现（命令目录 / Skills 目录 / MCP 配置），来源在
              <a href="/admin/capabilities.html">能力配置</a> 中维护；此处只读展示当前可用版本。
            </template>
          </el-alert>

          <el-empty v-if="catalog.length === 0" description="目录为空，请先在能力配置中添加扫描来源"
            :image-size="60" />
          <template v-else>
            <h3 class="section-title">命令（COMMAND）</h3>
            <el-table :data="catalogByKind.COMMAND" empty-text="无可用命令" size="small">
              <el-table-column prop="identifier" label="标识符" min-width="180"></el-table-column>
              <el-table-column prop="version" label="版本" width="120"></el-table-column>
              <el-table-column prop="displayName" label="名称" min-width="160"></el-table-column>
              <el-table-column prop="description" label="描述" min-width="240"></el-table-column>
              <el-table-column prop="hash" label="Hash" min-width="220">
                <template #default="scope">
                  <span class="mono-text hash-text">{{ scope.row.hash }}</span>
                </template>
              </el-table-column>
            </el-table>

            <h3 class="section-title">Skills（SKILL）</h3>
            <el-table :data="catalogByKind.SKILL" empty-text="无可用 Skills" size="small">
              <el-table-column prop="identifier" label="标识符" min-width="180"></el-table-column>
              <el-table-column prop="version" label="版本" width="120"></el-table-column>
              <el-table-column prop="description" label="描述" min-width="240"></el-table-column>
              <el-table-column prop="hash" label="Hash" min-width="220">
                <template #default="scope">
                  <span class="mono-text hash-text">{{ scope.row.hash }}</span>
                </template>
              </el-table-column>
            </el-table>

            <h3 class="section-title">MCP Servers（MCP_SERVER）</h3>
            <el-table :data="catalogByKind.MCP_SERVER" empty-text="无可用 MCP Servers" size="small">
              <el-table-column prop="identifier" label="标识符" min-width="180"></el-table-column>
              <el-table-column prop="version" label="版本" width="120"></el-table-column>
              <el-table-column prop="maximumAccess" label="访问级别" width="110"></el-table-column>
              <el-table-column prop="transport" label="传输" width="170"></el-table-column>
              <el-table-column prop="description" label="描述" min-width="240"></el-table-column>
              <el-table-column prop="hash" label="Hash" min-width="220">
                <template #default="scope">
                  <span class="mono-text hash-text">{{ scope.row.hash }}</span>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </el-tab-pane>
      </el-tabs>

      <el-dialog v-model="dialogVisible" :title="editingId ? '编辑模式' : '新建模式'" width="640px">
        <el-form label-width="110px">
          <el-form-item label="标识符" required>
            <el-input
              v-model="form.identifier" :disabled="!!editingId"
              placeholder="小写字母/数字/._-，创建后不可改" maxlength="64" />
          </el-form-item>
          <el-form-item label="显示名称" required>
            <el-input v-model="form.displayName" maxlength="128" />
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="form.description" type="textarea" :rows="2" maxlength="2000" />
          </el-form-item>
          <el-form-item label="默认提示词">
            <el-input
              v-model="form.defaultPrompt" type="textarea" :rows="4" maxlength="16384"
              placeholder="角色定义、约束、输出要求；经 --append-system-prompt 下发" />
          </el-form-item>
          <el-form-item label="权限模式">
            <el-select v-model="form.permissionMode" clearable style="width: 220px">
              <el-option
                v-for="option in permissionOptions" :key="option"
                :label="option" :value="option" />
            </el-select>
          </el-form-item>
          <el-form-item label="模型覆盖">
            <el-input v-model="form.model" placeholder="留空用运行时默认" maxlength="128"
              style="width: 220px" />
          </el-form-item>
          <el-form-item label="effort">
            <el-select v-model="form.effort" clearable style="width: 220px">
              <el-option
                v-for="option in ['low', 'medium', 'high']" :key="option"
                :label="option" :value="option" />
            </el-select>
          </el-form-item>
          <el-form-item label="命令">
            <el-select v-model="form.commands" multiple filterable style="width: 100%">
              <el-option
                v-for="item in catalogByKind.COMMAND" :key="item.identifier + '@' + item.version"
                :label="`${item.displayName} (${item.version})`" :value="catalogValue(item)" />
            </el-select>
          </el-form-item>
          <el-form-item label="Skills">
            <el-select v-model="form.skills" multiple filterable style="width: 100%">
              <el-option
                v-for="item in catalogByKind.SKILL" :key="item.identifier + '@' + item.version"
                :label="`${item.displayName} (${item.version})`" :value="catalogValue(item)" />
            </el-select>
          </el-form-item>
          <el-form-item label="MCP Servers">
            <el-select v-model="form.mcpServers" multiple filterable style="width: 100%">
              <el-option
                v-for="item in catalogByKind.MCP_SERVER"
                :key="item.identifier + '@' + item.version"
                :label="`${item.identifier} (${item.maximumAccess}/${item.transport})`"
                :value="catalogValue(item)" />
            </el-select>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        </template>
      </el-dialog>
    </div>
  </admin-shell>
</template>

<script setup>
import { computed, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import {
  listAllModes, listModeTemplates, listCapabilityCatalog,
  createMode, updateMode, deleteMode, forkMode,
} from '../../api/mode.js';

/**
 * 管理后台模式管理页：全量模式维护（跨用户）+ 模板 fork + 能力目录只读浏览。
 *
 * @author zhourui
 * @since 2026/08/22
 */
const modes = ref([]);
const templates = ref([]);
const catalog = ref([]);
const activeTab = ref('modes');
const loading = ref(false);
const dialogVisible = ref(false);
const saving = ref(false);
const editingId = ref('');
const permissionOptions = ['acceptEdits', 'auto', 'bypassPermissions', 'manual', 'dontAsk', 'plan'];
const emptyForm = () => ({
  identifier: '', displayName: '', description: '', defaultPrompt: '',
  permissionMode: '', model: '', effort: '',
  commands: [], skills: [], mcpServers: [],
});
const form = ref(emptyForm());

const catalogByKind = computed(() => {
  const grouped = { COMMAND: [], SKILL: [], MCP_SERVER: [] };
  for (const item of catalog.value) {
    if (grouped[item.kind]) grouped[item.kind].push(item);
  }
  return grouped;
});

const load = async () => {
  loading.value = true;
  try {
    const [modeList, templateList, catalogList] = await Promise.all([
      listAllModes(), listModeTemplates(), listCapabilityCatalog(),
    ]);
    modes.value = modeList;
    templates.value = templateList;
    catalog.value = catalogList;
  } catch (error) {
    ElMessage.error(`加载失败: ${error.message}`);
  } finally {
    loading.value = false;
  }
};

const catalogValue = (item) => `${item.identifier}@${item.version}`;
const parseCatalogValue = (value) => {
  const separator = value.lastIndexOf('@');
  return { identifier: value.slice(0, separator), version: value.slice(separator + 1) };
};

/** 目录项 → 后端能力引用（hash 以目录当前值为准，保存后冻结进模式）。 */
const toRefRequest = (value, kind) => {
  const parsed = parseCatalogValue(value);
  const found = catalog.value.find(
    (item) => item.kind === kind
      && item.identifier === parsed.identifier
      && item.version === parsed.version);
  const base = {
    identifier: parsed.identifier,
    version: parsed.version,
    contentHash: found ? found.hash : '',
    sortOrder: 0,
  };
  if (kind === 'MCP_SERVER' && found) {
    return {
      ...base,
      maximumAccess: found.maximumAccess || 'READ',
      transport: found.transport || 'STDIO',
    };
  }
  return base;
};

const openCreate = () => {
  editingId.value = '';
  form.value = emptyForm();
  dialogVisible.value = true;
};

const openEdit = (mode) => {
  editingId.value = mode.id;
  form.value = {
    identifier: mode.identifier,
    displayName: mode.displayName,
    description: mode.description || '',
    defaultPrompt: mode.defaultPrompt || '',
    permissionMode: mode.permissionMode || '',
    model: mode.model || '',
    effort: mode.effort || '',
    commands: mode.commands.map((capability) => catalogValue(capability)),
    skills: mode.skills.map((capability) => catalogValue(capability)),
    mcpServers: mode.mcpServers.map((capability) => catalogValue(capability)),
  };
  dialogVisible.value = true;
};

const save = async () => {
  if (!form.value.displayName.trim()) {
    ElMessage.warning('显示名称必填');
    return;
  }
  if (!editingId.value && !form.value.identifier.trim()) {
    ElMessage.warning('标识符必填');
    return;
  }
  saving.value = true;
  try {
    const request = {
      identifier: form.value.identifier.trim(),
      displayName: form.value.displayName.trim(),
      description: form.value.description || null,
      defaultPrompt: form.value.defaultPrompt || null,
      permissionMode: form.value.permissionMode || null,
      model: form.value.model || null,
      effort: form.value.effort || null,
      commands: form.value.commands.map((value) => toRefRequest(value, 'COMMAND')),
      skills: form.value.skills.map((value) => toRefRequest(value, 'SKILL')),
      mcpServers: form.value.mcpServers.map((value) => toRefRequest(value, 'MCP_SERVER')),
    };
    if (editingId.value) {
      await updateMode(editingId.value, request);
      ElMessage.success('模式已更新');
    } else {
      await createMode(request);
      ElMessage.success('模式已创建');
    }
    dialogVisible.value = false;
    await load();
  } catch (error) {
    ElMessage.error(`保存失败: ${error.message}`);
  } finally {
    saving.value = false;
  }
};

const removeMode = async (mode) => {
  try {
    await ElMessageBox.confirm(
      `确定删除模式「${mode.displayName}」（归属 ${mode.ownerUsername || mode.ownerUserId}）？`
      + '进行中的会话不受影响。', '删除确认',
      { type: 'warning' });
  } catch {
    return;
  }
  try {
    await deleteMode(mode.id);
    ElMessage.success('已删除');
    await load();
  } catch (error) {
    ElMessage.error(`删除失败: ${error.message}`);
  }
};

const forkTemplate = async (template) => {
  try {
    const created = await forkMode(template.revisionId);
    ElMessage.success(`已复制为「${created.displayName}」`);
    await load();
  } catch (error) {
    ElMessage.error(`复制失败: ${error.message}`);
  }
};
</script>

<style scoped>
.section-title {
  margin: 20px 0 12px;
  color: #303133;
}
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
}
.card-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.card-desc {
  font-size: 13px;
  color: #606266;
  min-height: 36px;
  margin-bottom: 8px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.card-meta {
  font-size: 12px;
  color: #909399;
  margin-bottom: 10px;
}
.card-actions {
  display: flex;
  gap: 8px;
}
.hash-text {
  word-break: break-all;
  font-size: 12px;
  color: #909399;
}
</style>
