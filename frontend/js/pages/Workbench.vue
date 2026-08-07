<template>
  <div class="workbench-shell">
    <el-alert
      v-if="errorMessage"
      class="workbench-global-error"
      type="error"
      show-icon
      closable
      :title="errorMessage"
      @close="clearError"
    />

    <div :class="['workbench-body', { 'sidebar-collapsed': sidebarCollapsed }]">
      <aside :class="['workbench-sidebar', { collapsed: sidebarCollapsed }]">
        <div class="workbench-sidebar-actions">
          <template v-if="!sidebarCollapsed">
            <el-button text @click="goHome">← 返回对话</el-button>
            <el-button type="primary" @click="openCreateDialog">
              <el-icon><plus /></el-icon>
              新建
            </el-button>
            <el-button text :loading="listLoading" title="刷新列表" @click="refreshList">
              <el-icon><refresh /></el-icon>
            </el-button>
          </template>
          <el-button
            text
            class="workbench-sidebar-toggle"
            :title="sidebarCollapsed ? '展开侧栏' : '收起侧栏'"
            :data-test="sidebarCollapsed ? 'expand-sidebar' : 'collapse-sidebar'"
            @click="toggleSidebar"
          >
            <el-icon><expand v-if="sidebarCollapsed" /><fold v-else /></el-icon>
          </el-button>
        </div>

        <div v-if="!sidebarCollapsed" v-loading="listLoading" class="workbench-list">
          <button
            v-for="item in workbenches"
            :key="item.id"
            type="button"
            :class="['workbench-list-item', { active: detail && detail.id === item.id }]"
            @click="selectWorkbench(item.id)"
          >
            <span class="workbench-list-title">{{ item.title }}</span>
            <span class="workbench-list-repository">
              {{ item.primaryRepositoryKey }} · {{ item.repositoryCount }} 仓
            </span>
            <span class="workbench-list-meta">
              <span :class="['workbench-status-dot', item.status.toLowerCase()]"></span>
              {{ workbenchStatusLabel(item.status) }}
              <span>·</span>
              <span>{{ formatTime(item.updatedAt) }}</span>
            </span>
          </button>
          <div v-if="!listLoading && workbenches.length === 0" class="workbench-empty-list">
            暂无 Workbench
          </div>
        </div>
      </aside>

      <main v-loading="detailLoading" class="workbench-main">
        <section v-if="!detail" class="workbench-welcome">
          <div class="workbench-welcome-mark">W</div>
          <h1>按自定义阶段推进工作</h1>
          <p>选择左侧 Workbench，或先检查本地 Workspace 并创建新的工作台。</p>
          <el-button type="primary" size="large" @click="openCreateDialog">
            创建第一个 Workbench
          </el-button>
        </section>

        <template v-else>
          <section class="workbench-detail-header">
            <el-popover placement="bottom-start" trigger="click" :width="360">
              <template #reference>
                <el-button text data-test="repository-scope-popover">
                  {{ detail.repositoryScope.primaryRepositoryKey }}
                  <span>·</span>
                  {{ detail.repositoryScope.repositories.length }} 个仓库
                </el-button>
              </template>
              <ul aria-label="冻结 Repository Scope">
                <li
                  v-for="repository in detail.repositoryScope.repositories"
                  :key="repository.repositoryKey"
                >
                  <strong>{{ repository.repositoryKey }}</strong>
                  <span>{{ repositoryRelativePathLabel(repository.relativePath) }}</span>
                  <el-tag v-if="repository.primary" size="small" type="success">主仓</el-tag>
                </li>
              </ul>
            </el-popover>
            <div class="workbench-detail-badges">
              <span :class="['agent-tag', 'agent-tag-' + detail.agentType.toLowerCase()]">{{ detail.agentType === 'CLAUDE' ? 'Claude' : 'Codex' }}</span>
              <el-tag :type="detail.status === 'ARCHIVED' ? 'info' : 'success'">
                {{ workbenchStatusLabel(detail.status) }}
              </el-tag>
              <el-tag v-if="detail.environment" effect="plain">{{ detail.environment }}</el-tag>
              <el-tag v-if="detail.useWorktree" type="warning" effect="plain">worktree: {{ detail.worktreeBranch || 'wb' }}</el-tag>
              <span class="workbench-version">v{{ detail.version }}</span>
            </div>
          </section>

          <nav class="workbench-stages" aria-label="Workbench 阶段">
            <button
              v-for="(stage, index) in detail.stages"
              :key="stage.stageInstanceIdentifier"
              type="button"
              :class="['workbench-stage', {
                active: selectedStageInstanceIdentifier === stage.stageInstanceIdentifier,
              }]"
              @click="selectStage(stage.stageInstanceIdentifier)"
            >
              <span class="workbench-stage-number">{{ index + 1 }}</span>
              <span>{{ stage.displayName }}</span>
            </button>
            <span class="workbench-stage-spacer"></span>
            <el-button
              plain
              size="small"
              data-test="toggle-document-pane"
              :title="isMobile ? '打开文档区' : desktopMode === 'COLLAPSED' ? '显示文档区' : '隐藏文档区'"
              @click="toggleDocumentPane"
            >
              <el-icon><document /></el-icon>
              文档
            </el-button>
            <el-button
              plain
              size="small"
              data-test="open-run-history"
              @click="openRunHistory"
            >
              运行记录
            </el-button>
            <el-button
              v-if="selectedWorkUnitView?.status === 'HUMAN_COMPLETED'"
              size="small"
              :loading="mutationLoading"
              :disabled="detail.status === 'ARCHIVED'"
              @click="reopenSelectedWorkUnit"
            >
              重新打开
            </el-button>
            <el-button
              v-else
              type="primary"
              size="small"
              :loading="mutationLoading"
              :disabled="!canCompleteSelectedWorkUnit"
              @click="completeSelectedWorkUnit"
            >
              阶段完成
            </el-button>
          </nav>

          <section
            ref="splitRoot"
            :class="[
              'workbench-workarea',
              `document-${desktopMode.toLowerCase()}`,
              { mobile: isMobile },
            ]"
            :style="splitStyle"
          >
            <workbench-conversation-panel
              v-if="isMobile || desktopMode !== 'MAXIMIZED'"
              :stage-instance-identifier="selectedStageInstanceIdentifier || ''"
              :stage-label="currentStageLabel"
              :workbench-id="workbenchId"
              :workspace-root="workspaceRoot"
              :model-value="composerText"
              :run-state="runState"
              :messages="conversationMessages"
              :messages-loading="messagesLoading"
              :has-older-messages="hasOlderConversationMessages"
              :older-messages-loading="olderMessagesLoading"
              :repository-keys="repositoryKeys"
              :attachments="pendingAttachments"
              :upload-items="workbenchUploadItems"
              :upload-notice="workbenchUploadNotice"
              :error="conversationError"
              :notice="conversationNotice"
              :allowed-run-modes="allowedRunModes"
              :selected-run-mode="selectedRunMode"
              :submitting="submitting"
              :stopping="stopping"
              :read-only="archived"
              :identity-ready="identityReady"
              :terminal-document-stale="Boolean(runState?.terminal && currentDocument?.stale)"
              @update:model-value="updateComposerText"
              @submit="submitConversation"
              @stop="stopConversation"
              @open-document="openRunDocument"
              @remove-attachment="removeAttachment"
              @upload-files="uploadWorkbenchFiles"
              @retry-upload="retryWorkbenchUpload"
              @remove-upload="removeWorkbenchUpload"
              @load-older-messages="loadOlderConversationMessages"
              @start-new-context="handleStartNewContext"
            />

            <button
              v-if="!isMobile && desktopMode === 'NORMAL'"
              type="button"
              :class="['workbench-resize-handle', { dragging }]"
              role="separator"
              aria-label="调整文档区宽度，双击恢复默认宽度"
              aria-orientation="vertical"
              title="拖动调整文档区宽度，双击恢复 35%"
              @pointerdown="beginResize"
              @pointermove="moveResize"
              @pointerup="endResize"
              @pointercancel="endResize"
              @dblclick="reset"
            ></button>

            <workbench-document-pane
              v-if="!isMobile && desktopMode !== 'COLLAPSED'"
              :repositories="detail.repositoryScope.repositories"
              :selected-repository-key="selectedRepositoryKey"
              :current-directory-path="currentDirectoryPath"
              :tree-entries="treeEntries"
              :tree-truncated="treeTruncated"
              :current-document="currentDocument"
              :loaded-content="loadedContent"
              :recent-documents="recentDocuments"
              :tree-loading="treeLoading"
              :content-loading="contentLoading"
              :download-loading="downloadLoading"
              :document-error="documentError"
              :attachment-selected="currentDocumentAttachmentSelected"
              @restore="restore"
              @select-repository="selectRepository"
              @open-directory="openDirectory"
              @parent-directory="navigateToParentDirectory"
              @open-document="openDocument"
              @refresh-document="refreshDocument"
              @download-document="downloadCurrent"
              @update-scroll="updateDocumentScrollTop"
              @attach-document="addAttachment"
              @close-document="closeDocument"
            />
          </section>

        </template>
      </main>
    </div>

    <el-drawer
      v-if="detail"
      v-model="mobileDrawerVisible"
      class="workbench-document-drawer"
      direction="rtl"
      size="100%"
      :with-header="false"
      destroy-on-close
    >
      <workbench-document-pane
        mobile
        :repositories="detail.repositoryScope.repositories"
        :selected-repository-key="selectedRepositoryKey"
        :current-directory-path="currentDirectoryPath"
        :tree-entries="treeEntries"
        :tree-truncated="treeTruncated"
        :current-document="currentDocument"
        :loaded-content="loadedContent"
        :recent-documents="recentDocuments"
        :tree-loading="treeLoading"
        :content-loading="contentLoading"
        :download-loading="downloadLoading"
        :document-error="documentError"
        :attachment-selected="currentDocumentAttachmentSelected"
        @close="mobileDrawerVisible = false"
        @select-repository="selectRepository"
        @open-directory="openDirectory"
        @parent-directory="navigateToParentDirectory"
        @open-document="openDocument"
        @refresh-document="refreshDocument"
        @download-document="downloadCurrent"
        @update-scroll="updateDocumentScrollTop"
        @attach-document="addAttachment"
      />
    </el-drawer>

    <workbench-run-history-drawer
      v-if="detail"
      :visible="runHistoryVisible"
      :loading-runs="runHistoryLoadingRuns"
      :loading-selection="runHistoryLoadingSelection"
      :loading-events="runHistoryLoadingEvents"
      :runs="historicalRuns"
      :selected-run-id="historicalRunId"
      :selected-run="historicalRun"
      :run-state="historicalRunState"
      :capability="historicalRunCapability"
      :history-error="runHistoryError"
      :capability-error="runHistoryCapabilityError"
      :has-more-runs="runHistoryHasMoreRuns"
      :has-more-events="runHistoryHasMoreEvents"
      @update:visible="runHistoryVisible = $event"
      @refresh="refreshRunHistory"
      @select-run="selectHistoricalRun"
      @load-more-runs="loadMoreHistoricalRuns"
      @load-more-events="loadMoreHistoricalEvents"
    />

    <el-dialog
      v-model="createDialogVisible"
      title="创建 Workbench"
      width="min(860px, 92vw)"
      destroy-on-close
    >
      <div class="workbench-create-grid">
        <label class="workbench-field workbench-field-wide">
          <span>Workspace Root</span>
          <div class="workbench-inspect-row">
            <el-input
              v-model="createForm.workspaceRoot"
              maxlength="4096"
              placeholder="输入已授权的本地 Workspace Root"
              @change="inspection = null"
            />
            <el-button type="primary" plain :loading="inspectLoading" @click="runInspection">
              检查仓库
            </el-button>
          </div>
        </label>

        <template v-if="inspection">
          <div class="workbench-inspection-summary workbench-field-wide">
            <span>{{ inspection.workspaceRootDisplay }}</span>
            <el-tag effect="plain">{{ inspection.source }}</el-tag>
          </div>
          <el-alert
            v-for="warning in inspection.warnings"
            :key="warning"
            class="workbench-field-wide"
            type="warning"
            :closable="false"
            show-icon
            :title="warning"
          />
          <div class="workbench-repository-picker workbench-field-wide">
            <div
              v-for="repository in inspection.repositories"
              :key="repository.repositoryKey"
              :class="['workbench-repository-option', { dirty: !repository.clean }]"
            >
              <label>
                <input
                  type="checkbox"
                  :checked="selectedRepositories.includes(repository.repositoryKey)"
                  @change="toggleRepository(repository.repositoryKey, $event)"
                >
                <span class="workbench-repository-copy">
                  <strong>{{ repository.repositoryKey }}</strong>
                  <small>{{ repository.relativePath || '.' }}</small>
                  <small>{{ repository.branch || '无分支' }} · {{ repository.headShort || '无 HEAD' }}</small>
                </span>
              </label>
              <div class="workbench-repository-flags">
                <el-tag size="small" :type="repository.clean ? 'success' : 'warning'">
                  {{ repository.clean ? 'clean' : 'dirty' }}
                </el-tag>
                <label v-if="selectedRepositories.includes(repository.repositoryKey)" class="workbench-primary-choice">
                  <input v-model="primaryRepository" type="radio" :value="repository.repositoryKey">
                  主仓
                </label>
              </div>
              <small v-for="warning in repository.warnings" :key="warning" class="workbench-repository-warning">
                {{ warning }}
              </small>
            </div>
            <div v-if="inspection.repositories.length === 0" class="workbench-no-repositories">
              未发现可选仓库
            </div>
          </div>
        </template>

        <label class="workbench-field">
          <span>标题</span>
          <el-input v-model="createForm.title" maxlength="512" show-word-limit />
        </label>
        <label class="workbench-field">
          <span>Agent</span>
          <el-radio-group v-model="createForm.agentType" style="width: 100%">
            <el-radio-button value="CODEX">Codex</el-radio-button>
            <el-radio-button value="CLAUDE">Claude</el-radio-button>
          </el-radio-group>
        </label>
        <label class="workbench-field">
          <span>环境</span>
          <el-input v-model="createForm.environment" maxlength="256" placeholder="test" />
        </label>
        <label class="workbench-field workbench-field-wide">
          <el-checkbox v-model="createForm.useWorktree">使用 worktree 隔离开发</el-checkbox>
        </label>
        <label class="workbench-field workbench-field-wide">
          <span>原始目标</span>
          <el-input
            v-model="createForm.originalGoal"
            type="textarea"
            :rows="5"
            maxlength="16000"
            show-word-limit
          />
        </label>

        <div class="workbench-field workbench-field-wide workbench-stage-picker">
          <span>选择阶段</span>
          <el-skeleton v-if="stageCatalogLoading" :rows="3" animated />
          <div v-else-if="selectableStageCatalog" class="workbench-stage-options">
            <label
              v-for="stage in selectableStageCatalog.stages"
              :key="stage.definitionIdentifier"
              class="workbench-stage-option"
            >
              <input
                v-model="selectedStageDefinitionIdentifiers"
                type="checkbox"
                :value="stage.definitionIdentifier"
              >
              <span>
                <strong>{{ stage.sequenceNumber }} · {{ stage.displayName }}</strong>
                <small>{{ stage.description }}</small>
              </span>
            </label>
            <el-empty
              v-if="selectableStageCatalog.stages.length === 0"
              description="暂无已发布阶段，请联系管理员"
              :image-size="64"
            />
          </div>
          <small class="workbench-stage-order-hint">
            阶段顺序由管理员发布配置决定，创建时只需选择。
          </small>
        </div>
      </div>

      <el-alert
        v-if="errorMessage"
        type="error"
        :closable="false"
        show-icon
        :title="errorMessage"
        style="margin-top: 16px"
      />
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="createLoading"
          :disabled="stageCatalogLoading || !selectableStageCatalog
            || selectedStageDefinitionIdentifiers.length === 0
            || !inspection || selectedRepositories.length === 0 || !primaryRepository"
          @click="submitCreate"
        >
          创建 Workbench
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
/**
 * Workbench Shell 页面。
 *
 * @author alex
 * @since 2026-08-01
 */
import { computed, onMounted, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import WorkbenchConversationPanel from '../components/WorkbenchConversationPanel.vue';
import WorkbenchDocumentPane from '../components/WorkbenchDocumentPane.vue';
import WorkbenchRunHistoryDrawer from '../components/WorkbenchRunHistoryDrawer.vue';
import { useWorkbenchConversation } from '../composables/useWorkbenchConversation.js';
import { useWorkbenchDocumentPane } from '../composables/useWorkbenchDocumentPane.js';
import { useWorkbenchShell } from '../composables/useWorkbenchShell.js';
import { useWorkbenchRunHistory } from '../composables/useWorkbenchRunHistory.js';
import { useWorkbenchUploadedAttachments } from '../composables/useWorkbenchUploadedAttachments.js';
import { stageStatusLabel } from '../lib/workbench-state.js';

function repositoryRelativePathLabel(relativePath) {
  const normalized = typeof relativePath === 'string'
    ? relativePath.trim().replace(/\\/g, '/')
    : '';
  if (!normalized || normalized === '.') return '.';
  if (normalized.startsWith('/')
    || normalized.startsWith('~/')
    || /^[A-Za-z]:\//.test(normalized)
    || normalized === '..'
    || normalized.startsWith('../')
    || normalized.includes('/../')) {
    return '路径已隐藏';
  }
  return normalized;
}

function withConversationReference(item, conversation) {
  return {
    ...item,
    conversationGeneration: conversation.generation,
    currentConversation: {
      sessionId: conversation.sessionId,
      generation: conversation.generation,
    },
    conversationHistory: item.conversationHistory.some(
      historical => historical.sessionId === conversation.sessionId,
    )
      ? item.conversationHistory
      : [...item.conversationHistory, {
          sessionId: conversation.sessionId,
          generation: conversation.generation,
        }],
  };
}

function withRestartedConversationReference(item, restarted) {
  return {
    ...withConversationReference(item, restarted),
    activeRun: null,
  };
}

export default {
  name: 'WorkbenchPage',
  components: {
    WorkbenchConversationPanel,
    WorkbenchDocumentPane,
    WorkbenchRunHistoryDrawer,
  },
  setup() {
    const shell = useWorkbenchShell();
    const workbenchId = computed(() => shell.detail.value?.id || null);
    const archived = computed(() => shell.detail.value?.status === 'ARCHIVED');
    const repositories = computed(() => shell.detail.value?.repositoryScope.repositories || []);
    const workspaceRoot = computed(() => shell.detail.value?.repositoryScope.workspaceRoot || '');
    const repositoryKeys = computed(() => repositories.value.map(
      repository => repository.repositoryKey,
    ));
    const documentPane = useWorkbenchDocumentPane({
      userId: shell.currentUserId,
      workbenchId,
      stageInstanceIdentifier: shell.selectedStageInstanceIdentifier,
      repositories,
    });
    const selectedWorkUnitView = computed(() => shell.selectedStageView.value);
    const conversationGeneration = computed(
      () => selectedWorkUnitView.value?.conversationGeneration ?? 0,
    );
    const currentConversationId = computed(
      () => selectedWorkUnitView.value?.currentConversation?.sessionId ?? null,
    );
    const activeRunId = computed(
      () => selectedWorkUnitView.value?.activeRun?.runId || null,
    );
    const activeWriteRunId = computed(
      () => shell.detail.value?.activeWriteRunId || null,
    );
    const stageStatus = computed(
      () => selectedWorkUnitView.value?.status ?? 'NOT_STARTED',
    );
    const allowedRunModes = computed(
      () => selectedWorkUnitView.value?.allowedRunModes ?? [],
    );
    const canCompleteSelectedWorkUnit = computed(() =>
      shell.detail.value?.status !== 'ARCHIVED'
      && ['NOT_STARTED', 'IN_PROGRESS'].includes(
        selectedWorkUnitView.value?.status ?? '',
      )
      && selectedWorkUnitView.value?.activeRun == null);
    const runHistory = useWorkbenchRunHistory({
      workbenchId,
      stageInstanceIdentifier: shell.selectedStageInstanceIdentifier,
    });
    const expectedVersion = computed(() => shell.detail.value?.version ?? null);
    const conversation = useWorkbenchConversation({
      ownerId: shell.currentUserId,
      workbenchId,
      stageInstanceIdentifier: shell.selectedStageInstanceIdentifier,
      conversationGeneration,
      currentConversationId,
      activeRunId,
      activeWriteRunId,
      expectedVersion,
      allowedRunModes,
      stageStatus,
      archived,
      onConversationEnsured: applyConversationEnsure,
      onConversationRestarted: applyConversationRestart,
      onSubmitted: applyRunSubmission,
      onTerminal: reloadAfterTerminal,
    });
    const uploadedAttachments = useWorkbenchUploadedAttachments({
      workbenchId,
      stageInstanceIdentifier: shell.selectedStageInstanceIdentifier,
      conversationGeneration,
      archived,
      combinedAttachmentCount: computed(
        () => conversation.pendingAttachments.value.length,
      ),
      onAvailable: attachment => conversation.addAttachment(attachment),
      onReleased: attachmentId => conversation.removeUploadedAttachment(attachmentId),
    });
    const currentDocumentAttachmentSelected = computed(() => (
      conversation.isAttachmentPending(
        documentPane.currentDocument.value?.reference?.repositoryKey,
        documentPane.currentDocument.value?.reference?.relativePath,
      )
    ));
    let lastDocumentEventId = 0;
    const currentStageLabel = computed(
      () => shell.selectedStageView.value?.displayName || '阶段',
    );

    function completeSelectedWorkUnit() {
      return shell.completeSelectedStage();
    }

    function reopenSelectedWorkUnit() {
      return shell.reopenSelectedStage();
    }

    function applyConversationEnsure(conversation) {
      if (!shell.detail.value) return;
      const stageInstanceIdentifier = shell.selectedStageInstanceIdentifier.value;
      if (stageInstanceIdentifier) {
        shell.detail.value = {
          ...shell.detail.value,
          version: conversation.workbenchVersion,
          stages: shell.detail.value.stages.map(item => (
            item.stageInstanceIdentifier === stageInstanceIdentifier
              ? withConversationReference(item, conversation)
              : item
          )),
        };
      }
    }

    function applyRunSubmission(submission, mode, attachments) {
      if (!shell.detail.value) return;
      uploadedAttachments.markSubmitted(
        (attachments || [])
          .filter(attachment => attachment.type === 'UPLOADED_CONVERSATION')
          .map(attachment => attachment.attachmentId),
      );
      const stageInstanceIdentifier =
        shell.selectedStageInstanceIdentifier.value;
      if (!stageInstanceIdentifier) return;
      shell.detail.value = {
        ...shell.detail.value,
        version: submission.workbenchVersion,
        activeWriteRunId: mode === 'MODIFY_WORKSPACE'
          ? submission.runId
          : shell.detail.value.activeWriteRunId,
        stages: shell.detail.value.stages.map(item => (
          item.stageInstanceIdentifier === stageInstanceIdentifier
            ? {
              ...item,
              status: submission.stageStatus,
              currentConversation: {
                sessionId: submission.sessionId,
                generation: item.conversationGeneration,
              },
              activeRun: {
                runId: submission.runId,
                runMode: mode,
                preparedAt: Date.now(),
              },
            }
            : item
        )),
      };
    }

    function applyConversationRestart(restarted) {
      if (!shell.detail.value) return;
      const stageInstanceIdentifier = shell.selectedStageInstanceIdentifier.value;
      if (stageInstanceIdentifier) {
        shell.detail.value = {
          ...shell.detail.value,
          version: restarted.workbenchVersion,
          stages: shell.detail.value.stages.map(item => (
            item.stageInstanceIdentifier === stageInstanceIdentifier
              && item.currentConversation?.sessionId === restarted.previousSessionId
              ? withRestartedConversationReference(item, restarted)
              : item
          )),
        };
      }
    }

    function reloadAfterTerminal() {
      const id = shell.detail.value?.id;
      if (id) void shell.selectWorkbench(id);
    }

    function updateComposerText(value) {
      conversation.updateComposerText(value);
    }

    async function openRunDocument(reference) {
      if (documentPane.isMobile.value) documentPane.openMobileDrawer();
      else if (documentPane.desktopMode.value === 'COLLAPSED') documentPane.restore();
      await documentPane.openDocument(reference);
    }

    function toggleDocumentPane() {
      if (documentPane.isMobile.value) documentPane.openMobileDrawer();
      else if (documentPane.desktopMode.value === 'COLLAPSED') documentPane.restore();
      else documentPane.collapse();
    }

    async function uploadWorkbenchFiles(files) {
      for (const file of Array.from(files || [])) {
        await uploadedAttachments.upload(file);
      }
    }

    async function retryWorkbenchUpload(clientId) {
      await uploadedAttachments.retry(clientId);
    }

    async function removeWorkbenchUpload(clientId) {
      await uploadedAttachments.remove(clientId);
    }

    async function handleStartNewContext() {
      try {
        await ElMessageBox.confirm(
          '旧会话历史将只读保留，新会话不会复制任何消息',
          '确认开始新对话上下文',
          {
            type: 'warning',
            confirmButtonText: '确认',
            cancelButtonText: '取消',
          },
        );
      } catch {
        return;
      }
      await conversation.restartConversation();
    }

    function workbenchStatusLabel(status) {
      if (status === 'ACTIVE') return '进行中';
      if (status === 'ARCHIVED') return '已归档';
      return status;
    }

    function formatTime(timestamp) {
      if (!timestamp) return '-';
      return new Intl.DateTimeFormat('zh-CN', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      }).format(new Date(timestamp));
    }

    function toggleRepository(repositoryKey, event) {
      if (event.target.checked) {
        if (!shell.selectedRepositories.value.includes(repositoryKey)) {
          shell.selectedRepositories.value = [
            ...shell.selectedRepositories.value,
            repositoryKey,
          ];
        }
      } else {
        shell.selectedRepositories.value = shell.selectedRepositories.value
          .filter((key) => key !== repositoryKey);
      }
      shell.ensurePrimaryRepository();
    }

    watch(
      () => [
        shell.currentUserId.value,
        workbenchId.value ?? '',
        shell.selectedStageInstanceIdentifier.value ?? '',
        conversationGeneration.value,
        conversation.runState.value?.context.runId ?? '',
        documentPane.documentEventScope.value,
      ],
      () => {
        lastDocumentEventId = 0;
      },
      { flush: 'sync' },
    );

    watch(
      () => conversation.runState.value?.staleDocuments ?? [],
      changes => {
        const scope = documentPane.documentEventScope.value;
        for (const changed of changes
          .filter(item => item.eventId > lastDocumentEventId)
          .slice()
          .sort((left, right) => left.eventId - right.eventId)) {
          lastDocumentEventId = changed.eventId;
          documentPane.receiveDocumentFileChanged(scope, {
            repositoryKey: changed.repositoryKey,
            relativePath: changed.path,
            changeType: changed.changeType,
            contentVersion: changed.contentVersion,
          });
        }
      },
      { flush: 'sync' },
    );

    onMounted(shell.initialize);

    return {
      ...shell,
      ...documentPane,
      ...conversation,
      workbenchUploadItems: uploadedAttachments.items,
      workbenchUploadNotice: uploadedAttachments.notice,
      workbenchId,
      archived,
      repositories,
      repositoryKeys,
      workspaceRoot,
      currentDocumentAttachmentSelected,
      runHistoryVisible: runHistory.visible,
      runHistoryLoadingRuns: runHistory.loadingRuns,
      runHistoryLoadingSelection: runHistory.loadingSelection,
      runHistoryLoadingEvents: runHistory.loadingEvents,
      historicalRuns: runHistory.runs,
      historicalRunId: runHistory.selectedRunId,
      historicalRun: runHistory.selectedRun,
      historicalRunState: runHistory.runState,
      historicalRunCapability: runHistory.capability,
      runHistoryError: runHistory.historyError,
      runHistoryCapabilityError: runHistory.capabilityError,
      runHistoryHasMoreRuns: runHistory.hasMoreRuns,
      runHistoryHasMoreEvents: runHistory.hasMoreEvents,
      openRunHistory: runHistory.open,
      refreshRunHistory: runHistory.refresh,
      selectHistoricalRun: runHistory.selectRun,
      loadMoreHistoricalRuns: runHistory.loadMoreRuns,
      loadMoreHistoricalEvents: runHistory.loadMoreEvents,
      currentStageLabel,
      selectedWorkUnitView,
      canCompleteSelectedWorkUnit,
      completeSelectedWorkUnit,
      reopenSelectedWorkUnit,
      stageStatusLabel,
      workbenchStatusLabel,
      formatTime,
      toggleRepository,
      updateComposerText,
      openRunDocument,
      toggleDocumentPane,
      uploadWorkbenchFiles,
      retryWorkbenchUpload,
      removeWorkbenchUpload,
      handleStartNewContext,
      repositoryRelativePathLabel,
    };
  },
};
</script>
