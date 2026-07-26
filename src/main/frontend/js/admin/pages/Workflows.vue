<template>
  <admin-shell active="workflows" @ready="loadAll">
    <template #header-actions>
      <el-button text :loading="loading" @click="loadAll">刷新</el-button>
    </template>

    <div class="view-wrap">
      <div class="conv-toolbar">
        <el-button type="primary" size="small" @click="openCreate">新建</el-button>
        <el-button size="small" :loading="loading" @click="loadAll">刷新</el-button>
      </div>

      <el-table
v-loading="loading" :data="workflows" size="small" empty-text="暂无工作流"
                @row-click="selectWorkflow">
        <el-table-column label="名称" min-width="220">
          <template #default="{ row }">
            <div
data-test="workflow-row" :data-name="row.name"
                 style="font-weight: 600; color: #303133; word-break: break-word;">{{ row.name }}</div>
            <div class="muted" style="font-size: 12px; word-break: break-all;">{{ row.workingDir }}</div>
          </template>
        </el-table-column>
        <el-table-column label="Agent" width="90" prop="agentType"></el-table-column>
        <el-table-column label="步骤" width="70">
          <template #default="{ row }">{{ row.steps ? row.steps.length : 0 }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" text @click.stop="openRun(row)">运行</el-button>
            <el-button size="small" text @click.stop="openEdit(row)">编辑</el-button>
            <el-button size="small" text @click.stop="selectWorkflow(row)">历史</el-button>
            <el-button size="small" type="danger" text @click.stop="deleteWorkflow(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div style="margin-top: 14px;">
        <div class="section-title">执行历史</div>
        <el-table
v-loading="executionLoading" :data="executions" size="small" empty-text="请选择工作流或暂无执行记录"
                  @row-click="openExecution">
          <el-table-column label="执行 ID" min-width="220">
            <template #default="{ row }">
              <span data-test="workflow-execution-row" :data-id="row.id">{{ row.id }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="开始时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column label="结束时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.finishedAt) }}</template>
          </el-table-column>
          <el-table-column prop="errorMessage" label="错误" min-width="220" show-overflow-tooltip></el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" text @click.stop="openExecution(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog
v-model="editOpen" :title="editForm.id ? '编辑工作流' : '新建工作流'"
               :width="isMobile ? '96%' : '860px'" :close-on-click-modal="false">
      <el-form label-position="top" :model="editForm" size="small">
        <el-form-item label="名称">
          <el-input v-model="editForm.name" maxlength="120" show-word-limit></el-input>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }"></el-input>
        </el-form-item>
        <el-form-item label="Agent 类型">
          <el-select v-model="editForm.agentType" style="width: 180px;">
            <el-option label="Codex" value="CODEX"></el-option>
            <el-option label="Claude" value="CLAUDE"></el-option>
          </el-select>
          <el-switch v-model="editForm.enabled" style="margin-left: 16px;" active-text="启用" inactive-text="停用"></el-switch>
        </el-form-item>
        <el-form-item label="工作目录">
          <el-input v-model="editForm.workingDir" placeholder="E:/ai_workspace/agent-web"></el-input>
        </el-form-item>
        <div class="conv-toolbar" style="padding-left: 0;">
          <span class="section-title" style="margin: 0;">步骤</span>
          <el-button size="small" @click="addStep">添加步骤</el-button>
        </div>
        <div v-for="(step, index) in editForm.steps" :key="index" style="border-top: 1px solid #ebeef5; padding-top: 10px; margin-top: 10px;">
          <el-row :gutter="10">
            <el-col :span="isMobile ? 24 : 8">
              <el-form-item label="步骤名">
                <el-input v-model="step.name" placeholder="review"></el-input>
              </el-form-item>
            </el-col>
            <el-col :span="isMobile ? 24 : 8">
              <el-form-item label="超时秒数">
                <el-input-number v-model="step.timeoutSeconds" :min="0" :step="60" style="width: 100%;"></el-input-number>
              </el-form-item>
            </el-col>
            <el-col :span="isMobile ? 24 : 8" style="display: flex; align-items: center;">
              <el-button size="small" type="danger" text :disabled="editForm.steps.length <= 1" @click="removeStep(index)">删除步骤</el-button>
            </el-col>
          </el-row>
          <el-form-item label="Prompt 模板">
            <el-input v-model="step.promptTemplate" type="textarea" :autosize="{ minRows: 4, maxRows: 12 }"></el-input>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="editOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveWorkflow">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="runOpen" title="运行工作流" :width="isMobile ? '96%' : '620px'" :close-on-click-modal="false">
      <div v-if="runTarget" style="font-weight: 600; margin-bottom: 10px;">{{ runTarget.name }}</div>
      <el-input v-model="runInputs" type="textarea" :autosize="{ minRows: 8, maxRows: 18 }"></el-input>
      <template #footer>
        <el-button :disabled="running" @click="runOpen = false">取消</el-button>
        <el-button type="primary" :loading="running" @click="submitRun">运行</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailOpen" title="执行详情" :size="isMobile ? '100%' : '760px'">
      <div v-if="detail">
        <div class="drawer-meta">
          <div>执行 ID：{{ detail.id }}</div>
          <div>状态：{{ detail.status }}</div>
          <div>开始：{{ fmtTime(detail.startedAt) }}</div>
          <div v-if="detail.finishedAt">结束：{{ fmtTime(detail.finishedAt) }}</div>
          <div v-if="detail.errorMessage">错误：{{ detail.errorMessage }}</div>
        </div>
        <el-collapse>
          <el-collapse-item
v-for="step in detail.steps" :key="step.id"
                            :title="(step.stepIndex + 1) + '. ' + step.stepName + ' · ' + step.status"
                            :name="step.id">
            <div class="section-title">Prompt</div>
            <pre style="white-space: pre-wrap; word-break: break-word;">{{ step.prompt }}</pre>
            <div class="section-title">Output</div>
            <pre style="white-space: pre-wrap; word-break: break-word;">{{ step.output || '' }}</pre>
            <template v-if="step.errorMessage">
              <div class="section-title">Error</div>
              <pre style="white-space: pre-wrap; word-break: break-word; color: #f56c6c;">{{ step.errorMessage }}</pre>
            </template>
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-drawer>
  </admin-shell>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';

const workflows = ref<any[]>([]);
const executions = ref<any[]>([]);
const selectedWorkflow = ref<any>(null);
const loading = ref<boolean>(false);
const executionLoading = ref<boolean>(false);
const editOpen = ref<boolean>(false);
const saving = ref<boolean>(false);
const runOpen = ref<boolean>(false);
const running = ref<boolean>(false);
const runTarget = ref<any>(null);
const runInputs = ref<string>('{\n  "branch": "main"\n}');
const detailOpen = ref<boolean>(false);
const detail = ref<any>(null);
const isMobile = ref<boolean>(window.innerWidth <= 768);
const editForm = reactive(emptyForm());

window.addEventListener('resize', () => { isMobile.value = window.innerWidth <= 768; });

function emptyForm() {
  return {
    id: '',
    name: '',
    description: '',
    agentType: 'CODEX',
    workingDir: '',
    enabled: true,
    steps: [emptyStep()]
  };
}

function emptyStep() {
  return { name: 'review', promptTemplate: '', timeoutSeconds: 1800 };
}

async function loadAll(): Promise<void> {
  await loadWorkflows();
  if (selectedWorkflow.value) {
    await loadExecutions(selectedWorkflow.value.id);
  }
}

async function loadWorkflows(): Promise<void> {
  loading.value = true;
  try {
    const data = await fetch('/api/admin-workflows').then(r => r.json());
    workflows.value = Array.isArray(data) ? data : [];
    if (!selectedWorkflow.value && workflows.value.length > 0) {
      await selectWorkflow(workflows.value[0]);
    }
  } catch (e: any) {
    ElMessage.error('加载工作流失败: ' + e);
  } finally {
    loading.value = false;
  }
}

async function selectWorkflow(row: any): Promise<void> {
  selectedWorkflow.value = row;
  await loadExecutions(row.id);
}

async function loadExecutions(workflowId: string): Promise<void> {
  executionLoading.value = true;
  try {
    const params = new URLSearchParams({ workflowId, page: '1', size: '50' });
    const data = await fetch('/api/admin-workflow-executions?' + params.toString()).then(r => r.json());
    executions.value = Array.isArray(data) ? data : [];
  } catch (e: any) {
    ElMessage.error('加载执行历史失败: ' + e);
  } finally {
    executionLoading.value = false;
  }
}

function openCreate(): void {
  Object.assign(editForm, emptyForm());
  editOpen.value = true;
}

function openEdit(row: any): void {
  Object.assign(editForm, {
    id: row.id,
    name: row.name,
    description: row.description || '',
    agentType: row.agentType || 'CODEX',
    workingDir: row.workingDir || '',
    enabled: row.enabled !== false,
    steps: (row.steps || []).map((s: any) => ({
      name: s.name,
      promptTemplate: s.promptTemplate,
      timeoutSeconds: s.timeoutSeconds || 0
    }))
  });
  if (editForm.steps.length === 0) {
    editForm.steps.push(emptyStep());
  }
  editOpen.value = true;
}

function addStep(): void {
  editForm.steps.push(emptyStep());
}

function removeStep(index: number): void {
  editForm.steps.splice(index, 1);
}

async function saveWorkflow(): Promise<void> {
  saving.value = true;
  try {
    const payload = {
      name: editForm.name,
      description: editForm.description,
      agentType: editForm.agentType,
      workingDir: editForm.workingDir,
      enabled: editForm.enabled,
      steps: editForm.steps
    };
    const url = editForm.id ? '/api/admin-workflows/' + encodeURIComponent(editForm.id) : '/api/admin-workflows';
    const res = await fetch(url, {
      method: editForm.id ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    if (!res.ok) {
      throw new Error(await res.text());
    }
    const saved = await res.json();
    ElMessage.success('已保存');
    editOpen.value = false;
    selectedWorkflow.value = saved;
    await loadWorkflows();
    await loadExecutions(saved.id);
  } catch (e: any) {
    ElMessage.error('保存失败: ' + (e.message || e));
  } finally {
    saving.value = false;
  }
}

function openRun(row: any): void {
  runTarget.value = row;
  runInputs.value = '{\n  "branch": "main"\n}';
  runOpen.value = true;
}

async function submitRun(): Promise<void> {
  running.value = true;
  try {
    const parsed = JSON.parse(runInputs.value || '{}');
    const res = await fetch('/api/admin-workflows/' + encodeURIComponent(runTarget.value.id) + '/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ inputs: parsed })
    });
    if (!res.ok) {
      throw new Error(await res.text());
    }
    const data = await res.json();
    ElMessage.success('已触发执行');
    runOpen.value = false;
    selectedWorkflow.value = runTarget.value;
    await loadExecutions(runTarget.value.id);
    if (data.executionId) {
      await openExecution({ id: data.executionId });
    }
  } catch (e: any) {
    ElMessage.error('运行失败: ' + (e.message || e));
  } finally {
    running.value = false;
  }
}

async function openExecution(row: any): Promise<void> {
  try {
    detail.value = await fetch('/api/admin-workflow-executions/' + encodeURIComponent(row.id)).then(r => r.json());
    detailOpen.value = true;
  } catch (e: any) {
    ElMessage.error('加载执行详情失败: ' + e);
  }
}

async function deleteWorkflow(row: any): Promise<void> {
  try {
    await ElMessageBox.confirm('删除后执行历史仍保留。', '删除工作流', { type: 'warning' });
    const res = await fetch('/api/admin-workflows/' + encodeURIComponent(row.id), { method: 'DELETE' });
    if (!res.ok) {
      throw new Error(await res.text());
    }
    if (selectedWorkflow.value && selectedWorkflow.value.id === row.id) {
      selectedWorkflow.value = null;
      executions.value = [];
    }
    ElMessage.success('已删除');
    await loadWorkflows();
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败: ' + (e.message || e));
    }
  }
}

function fmtTime(iso: string): string {
  if (!iso) {
    return '-';
  }
  return String(iso).replace('T', ' ').replace(/\..*$/, '').replace('Z', '').slice(0, 19);
}

function statusType(status: string): string {
  const map: Record<string, string> = { RUNNING: 'warning', SUCCEEDED: 'success', FAILED: 'danger' };
  return map[status] || 'info';
}
</script>