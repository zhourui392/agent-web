<template>
  <admin-shell active="settings" @ready="loadSettings">
    <template #header-actions>
      <el-button text @click="loadSettings" :loading="loading">刷新</el-button>
    </template>

    <div class="view-wrap" v-loading="loading" style="max-width: 760px;">
      <el-card shadow="never">
        <template #header>
          <div style="font-weight: 700;">默认模型</div>
        </template>

        <el-form label-position="top" size="default">
          <el-form-item label="对话默认模型">
            <el-select v-model="form.chatDefaultAgent" style="width: 100%;" data-test="chat-agent-select">
              <el-option v-for="o in options" :key="o" :label="o" :value="o"></el-option>
            </el-select>
            <div class="muted" style="font-size: 12px; margin-top: 6px; line-height: 1.6;">
              新对话默认选中的模型。<strong>修改后所有用户下次打开页面会被强制切换到该模型</strong>(覆盖各自浏览器记忆),之后单次会话内仍可临时切换。
            </div>
          </el-form-item>

          <el-form-item>
            <el-button type="primary" :loading="savingAgentModels" @click="saveAgentModels" data-test="save-settings">保存默认模型</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card shadow="never" style="margin-top: 16px;">
        <template #header>
          <div style="font-weight: 700;">工作空间与目录授权</div>
        </template>

        <el-form label-position="top" size="default">
          <el-form-item label="默认工作空间">
            <el-input v-model="form.defaultWorkspace" placeholder="/home/service/workspace" data-test="default-workspace"></el-input>
            <div class="muted" style="font-size: 12px; margin-top: 6px; line-height: 1.6;">
              首页首次加载时进入的目录，必须同时出现在下方允许根目录中。
            </div>
          </el-form-item>

          <el-form-item label="允许的工作空间根目录（每行一个）">
            <el-input v-model="form.workspaceRootsText" type="textarea" :rows="5"
                      placeholder="/home/service/workspace" data-test="workspace-roots"></el-input>
            <div class="muted" style="font-size: 12px; margin-top: 6px; line-height: 1.6;">
              同时约束目录浏览、文件读写、会话、Worktree 和 Workspace Context。保存时目录必须真实存在；更新后立即生效。
            </div>
          </el-form-item>

          <el-form-item label="仅上传额外放行根目录（可选，每行一个）">
            <el-input v-model="form.uploadRootsText" type="textarea" :rows="3"
                      placeholder="/home/service/.agent-web" data-test="upload-roots"></el-input>
            <div class="muted" style="font-size: 12px; margin-top: 6px; line-height: 1.6;">
              只额外授权上传，不开放目录浏览、下载或删除。
            </div>
          </el-form-item>

          <el-form-item>
            <el-button type="primary" :loading="savingWorkspaces" @click="saveWorkspaces"
                       data-test="save-workspaces">保存工作空间配置</el-button>
            <el-button :loading="resettingWorkspaces" @click="resetWorkspaces"
                       data-test="reset-workspaces">恢复配置文件默认值</el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </div>
  </admin-shell>
</template>

<script>
import { fetchJson } from '../../lib/admin-fetch.js';
import { pathsToText, textToPaths } from '../settings-utils.js';
import { ref, reactive } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';

export default {
  setup() {
    const options = ref(['CLAUDE', 'CODEX']);
    const form = reactive({
      chatDefaultAgent: '',
      defaultWorkspace: '',
      workspaceRootsText: '',
      uploadRootsText: ''
    });
    const loading = ref(false);
    const savingAgentModels = ref(false);
    const savingWorkspaces = ref(false);
    const resettingWorkspaces = ref(false);

    function applyWorkspaceSettings(data) {
      form.defaultWorkspace = data.defaultWorkspace || '';
      form.workspaceRootsText = pathsToText(data.workspaceRoots);
      form.uploadRootsText = pathsToText(data.uploadRoots);
    }

    async function loadSettings() {
      loading.value = true;
      try {
        const [agentModels, workspaces] = await Promise.all([
          fetchJson('/api/admin-settings/agent-models'),
          fetchJson('/api/admin-settings/workspaces')
        ]);
        if (Array.isArray(agentModels.options) && agentModels.options.length > 0) {
          options.value = agentModels.options;
        }
        form.chatDefaultAgent = agentModels.chatDefaultAgent || '';
        applyWorkspaceSettings(workspaces);
      } catch (e) {
        ElMessage.error('加载设置失败: ' + e);
      } finally {
        loading.value = false;
      }
    }

    async function saveAgentModels() {
      savingAgentModels.value = true;
      try {
        const data = await fetchJson('/api/admin-settings/agent-models', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            chatDefaultAgent: form.chatDefaultAgent
          })
        });
        form.chatDefaultAgent = data.chatDefaultAgent || form.chatDefaultAgent;
        ElMessage.success('默认模型已保存');
      } catch (e) {
        ElMessage.error('保存失败: ' + e.message);
      } finally {
        savingAgentModels.value = false;
      }
    }

    async function saveWorkspaces() {
      savingWorkspaces.value = true;
      try {
        const data = await fetchJson('/api/admin-settings/workspaces', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            defaultWorkspace: form.defaultWorkspace,
            workspaceRoots: textToPaths(form.workspaceRootsText),
            uploadRoots: textToPaths(form.uploadRootsText)
          })
        });
        applyWorkspaceSettings(data);
        ElMessage.success('工作空间配置已保存并生效');
      } catch (e) {
        ElMessage.error('保存失败: ' + e.message);
      } finally {
        savingWorkspaces.value = false;
      }
    }

    async function resetWorkspaces() {
      try {
        await ElMessageBox.confirm(
          '将删除数据库中的工作空间配置，并恢复为配置文件默认值。是否继续？',
          '恢复默认值',
          { type: 'warning', confirmButtonText: '恢复', cancelButtonText: '取消' }
        );
      } catch (e) {
        return;
      }
      resettingWorkspaces.value = true;
      try {
        const data = await fetchJson('/api/admin-settings/workspaces', { method: 'DELETE' });
        applyWorkspaceSettings(data);
        ElMessage.success('已恢复配置文件默认值');
      } catch (e) {
        ElMessage.error('恢复失败: ' + e.message);
      } finally {
        resettingWorkspaces.value = false;
      }
    }

    return {
      options,
      form,
      loading,
      savingAgentModels,
      savingWorkspaces,
      resettingWorkspaces,
      loadSettings,
      saveAgentModels,
      saveWorkspaces,
      resetWorkspaces
    };
  }
};
</script>