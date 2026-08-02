<template>
  <div class="workbench-shell">
    <header class="workbench-topbar">
      <button class="workbench-brand" type="button" @click="goHome">
        <span class="workbench-brand-mark">W</span>
        <span>本地开发 Workbench</span>
      </button>
      <span class="workbench-topbar-spacer"></span>
      <span class="workbench-user">{{ username || '当前用户' }}</span>
      <el-button plain @click="goHome">返回对话</el-button>
      <el-button v-if="authEnabled" plain @click="doLogout">登出</el-button>
    </header>

    <el-alert
      v-if="errorMessage"
      class="workbench-global-error"
      type="error"
      show-icon
      closable
      :title="errorMessage"
      @close="clearError"
    />

    <div class="workbench-body">
      <aside class="workbench-sidebar">
        <div class="workbench-sidebar-actions">
          <el-button type="primary" @click="openCreateDialog">
            <el-icon><plus /></el-icon>
            新建 Workbench
          </el-button>
          <el-button text :loading="listLoading" title="刷新列表" @click="refreshList">
            <el-icon><refresh /></el-icon>
          </el-button>
        </div>

        <div v-loading="listLoading" class="workbench-list">
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
          <h1>从需求分析到人工 Review</h1>
          <p>选择左侧 Workbench，或先检查本地 Workspace 并创建新的工作台。</p>
          <el-button type="primary" size="large" @click="openCreateDialog">
            创建第一个 Workbench
          </el-button>
        </section>

        <template v-else>
          <section class="workbench-detail-header">
            <div class="workbench-detail-heading">
              <div class="workbench-detail-eyebrow">
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
              </div>
              <h1>{{ detail.title }}</h1>
              <p>{{ detail.originalGoal }}</p>
            </div>
            <div class="workbench-detail-badges">
              <el-tag :type="detail.status === 'ARCHIVED' ? 'info' : 'success'">
                {{ workbenchStatusLabel(detail.status) }}
              </el-tag>
              <el-tag effect="plain">{{ detail.agentType }}</el-tag>
              <el-tag v-if="detail.environment" effect="plain">{{ detail.environment }}</el-tag>
              <span class="workbench-version">v{{ detail.version }}</span>
            </div>
          </section>

          <nav class="workbench-phases" aria-label="Workbench 阶段">
            <button
              v-for="(item, index) in WORKBENCH_PHASES"
              :key="item.phase"
              type="button"
              :class="['workbench-phase', { active: selectedPhase === item.phase }]"
              @click="selectPhase(item.phase)"
            >
              <span class="workbench-phase-number">{{ index + 1 }}</span>
              <span class="workbench-phase-copy">
                <strong>{{ item.label }}</strong>
                <small>{{ phaseStatusLabel(phaseView(item.phase)?.status || 'NOT_STARTED') }}</small>
              </span>
            </button>
          </nav>

          <section class="workbench-phase-toolbar">
            <div>
              <h2>{{ currentPhaseLabel }}</h2>
              <p>可以随时切换阶段；阶段状态仅由人工维护，不表示 Gate 或 PASS。</p>
              <el-tag
                data-test="phase-capability-status"
                :type="capabilityStatusType"
                effect="plain"
              >
                阶段能力：{{ capabilityStatusLabel }}
              </el-tag>
            </div>
            <div class="workbench-phase-actions">
              <el-button
                plain
                data-test="open-run-history"
                @click="openRunHistory"
              >
                运行记录
              </el-button>
              <el-button
                plain
                data-test="open-handoff-drawer"
                @click="openHandoffDrawer"
              >
                阶段交接
              </el-button>
              <el-dropdown
                trigger="click"
                data-test="phase-advanced-menu"
                @command="handlePhaseAdvancedCommand"
              >
                <el-button plain aria-label="阶段高级操作">⋯</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="open-capability">阶段能力</el-dropdown-item>
                    <el-dropdown-item
                      divided
                      command="restart-conversation"
                      data-test="restart-phase-conversation"
                      :disabled="!canRestartConversation"
                    >
                      {{ restarting ? '正在重启…' : '重启会话' }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <el-button
                v-if="selectedPhaseView?.status === 'HUMAN_COMPLETED'"
                :loading="mutationLoading"
                :disabled="detail.status === 'ARCHIVED'"
                @click="reopenSelectedPhase"
              >
                重新打开
              </el-button>
              <el-button
                v-else
                type="primary"
                :loading="mutationLoading"
                :disabled="!canCompleteSelectedPhase"
                @click="completeSelectedPhase"
              >
                人工完成
              </el-button>
            </div>
          </section>

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
              :phase="selectedPhase"
              :phase-label="currentPhaseLabel"
              :model-value="composerText"
              :run-mode="runMode"
              :run-state="runState"
              :messages="conversationMessages"
              :messages-loading="messagesLoading"
              :has-older-messages="hasOlderConversationMessages"
              :older-messages-loading="olderMessagesLoading"
              :repository-keys="repositoryKeys"
              :attachments="pendingAttachments"
              :upload-items="workbenchUploadItems"
              :upload-notice="workbenchUploadNotice"
              :connection-status="connectionStatus"
              :error="conversationError"
              :notice="conversationNotice"
              :submitting="submitting"
              :stopping="stopping"
              :read-only="archived"
              :identity-ready="identityReady"
              :handoff-ready="handoffReady"
              :modify-allowed="modifyAllowed"
              :modify-ready="selectedPhase !== 'REVIEW_REFACTOR' || reviewConfirmed"
              :write-run-blocked="writeRunBlocked"
              :mobile="isMobile"
              :document-collapsed="desktopMode === 'COLLAPSED'"
              :terminal-document-stale="Boolean(runState?.terminal && currentDocument?.stale)"
              @update:model-value="updateComposerText"
              @update:run-mode="updateRunMode"
              @submit="submitConversation"
              @stop="stopConversation"
              @open-document="openRunDocument"
              @open-document-pane="openMobileDrawer"
              @restore-document-pane="restore"
              @remove-attachment="removeAttachment"
              @upload-files="uploadWorkbenchFiles"
              @retry-upload="retryWorkbenchUpload"
              @remove-upload="removeWorkbenchUpload"
              @load-older-messages="loadOlderConversationMessages"
            >
              <template v-if="selectedPhase === 'REVIEW_REFACTOR'" #review>
                <workbench-review-panel
                  :model-value="composerText"
                  :opinion="reviewOpinion"
                  :confirmation="reviewConfirmation"
                  :loading="reviewLoading"
                  :saving="reviewSaving"
                  :confirming="reviewConfirming"
                  :error="reviewError"
                  :notice="reviewNotice"
                  :read-only="reviewReadOnly"
                  :draft-matches-opinion="reviewDraftMatchesOpinion"
                  :confirmed="reviewConfirmed"
                  :can-save="reviewCanSave"
                  :can-confirm="reviewCanConfirm"
                  :candidate="reviewCandidate"
                  :candidate-items="reviewCandidateItems"
                  :candidate-loading="reviewCandidateLoading"
                  :candidate-error="reviewCandidateError"
                  :can-generate-candidate="reviewCanGenerateCandidate"
                  @update:model-value="updateComposerText"
                  @save-opinion="saveReviewOpinion"
                  @confirm-modification="confirmReviewModification"
                  @generate-candidate="generateReviewCandidate"
                  @update-candidate-item="updateReviewCandidateItem"
                  @accept-candidate-item="acceptReviewCandidateItem"
                  @ignore-candidate-item="ignoreReviewCandidateItem"
                />
              </template>
              <template #operations>
                <workbench-operation-cards
                  :operations="phaseOperations"
                  :loading="operationLoading"
                  :deciding-id="operationDecidingId"
                  :error="operationError"
                  :notice="operationNotice"
                  :read-only="operationReadOnly"
                  :repositories="repositories"
                  :source-runs="operationSourceRuns"
                  :workbench-id="workbenchId"
                  :phase="selectedPhase"
                  :proposal-source-loading="operationSourceRunsLoading"
                  :proposing="operationProposing"
                  :proposal-created-token="operationProposalCreatedToken"
                  :proposal-disabled-reason="operationProposalDisabledReason"
                  @refresh="loadOperations"
                  @decide="decideOperation"
                  @prepare-proposal="prepareOperationProposal"
                  @propose="proposeOperation"
                />
              </template>
            </workbench-conversation-panel>

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
              :maximized="desktopMode === 'MAXIMIZED'"
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
              @collapse="collapse"
              @maximize="maximize"
              @restore="restore"
              @select-repository="selectRepository"
              @open-directory="openDirectory"
              @parent-directory="navigateToParentDirectory"
              @open-document="openDocument"
              @refresh-document="refreshDocument"
              @download-document="downloadCurrent"
              @update-scroll="updateDocumentScrollTop"
              @attach-document="addAttachment"
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

    <workbench-capability-drawer
      v-if="detail"
      :visible="capabilityDrawerVisible"
      :profile="capabilityProfile"
      :override="capabilityOverride"
      :draft="capabilityDraft"
      :loading="capabilityLoading"
      :saving="capabilitySaving"
      :error="capabilityError"
      :notice="capabilityNotice"
      :dirty="capabilityDirty"
      :can-restore-defaults="capabilityCanRestoreDefaults"
      :read-only="detail.status === 'ARCHIVED'"
      @update:visible="capabilityDrawerVisible = $event"
      @update:draft="updateCapabilityDraft"
      @refresh="refreshCapability"
      @save="saveCapabilityOverride"
      @restore-defaults="restoreCapabilityDefaults"
    />

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

    <workbench-handoff-drawer
      v-if="detail"
      :visible="handoffDrawerVisible"
      :current="handoffCurrent"
      :draft="handoffDraft"
      :source="handoffSource"
      :conflict="handoffConflict"
      :loading="handoffLoading"
      :saving="handoffSaving"
      :accepting="handoffAccepting"
      :candidate-generating="handoffCandidateGenerating"
      :candidate="handoffCandidate"
      :candidate-pending="handoffCandidatePending"
      :error="handoffError"
      :notice="handoffNotice"
      :dirty="handoffDirty"
      :read-only="handoffReadOnly"
      :keep-current-dismissed="keepCurrentDismissed"
      :phase="selectedPhase"
      @close="closeHandoffDrawer"
      @update:draft="updateHandoffDraft"
      @save="saveHandoff"
      @reload-current="adoptRemoteCurrent"
      @refresh-source="refreshHandoffSource"
      @accept-latest="acceptLatestSource"
      @keep-current="keepCurrentSource"
      @open-document="openHandoffDocument"
      @generate-candidate="generateHandoffCandidate"
      @apply-candidate-field="applyHandoffCandidateField"
      @ignore-candidate-field="ignoreHandoffCandidateField"
      @dismiss-candidate="dismissHandoffCandidate"
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
          <el-select v-model="createForm.agentType" style="width: 100%">
            <el-option label="Codex" value="CODEX" />
          </el-select>
          <small class="workbench-field-help">当前 Workbench 试点仅开放 Codex。</small>
        </label>
        <label class="workbench-field">
          <span>环境</span>
          <el-input v-model="createForm.environment" maxlength="256" placeholder="test" />
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
          :disabled="!inspection || selectedRepositories.length === 0 || !primaryRepository"
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
import WorkbenchCapabilityDrawer from '../components/WorkbenchCapabilityDrawer.vue';
import WorkbenchConversationPanel from '../components/WorkbenchConversationPanel.vue';
import WorkbenchDocumentPane from '../components/WorkbenchDocumentPane.vue';
import WorkbenchHandoffDrawer from '../components/WorkbenchHandoffDrawer.vue';
import WorkbenchOperationCards from '../components/WorkbenchOperationCards.vue';
import WorkbenchReviewPanel from '../components/WorkbenchReviewPanel.vue';
import WorkbenchRunHistoryDrawer from '../components/WorkbenchRunHistoryDrawer.vue';
import { useWorkbenchCapability } from '../composables/useWorkbenchCapability.js';
import { useWorkbenchConversation } from '../composables/useWorkbenchConversation.js';
import { useWorkbenchDocumentPane } from '../composables/useWorkbenchDocumentPane.js';
import { useWorkbenchHandoff } from '../composables/useWorkbenchHandoff.js';
import { useWorkbenchOperations } from '../composables/useWorkbenchOperations.js';
import { useWorkbenchReview } from '../composables/useWorkbenchReview.js';
import { useWorkbenchRunHistory } from '../composables/useWorkbenchRunHistory.js';
import { useWorkbenchUploadedAttachments } from '../composables/useWorkbenchUploadedAttachments.js';
import { openWorkbenchHandoffDocument } from '../lib/workbench-handoff-integration.js';
import { WORKBENCH_PHASES, phaseStatusLabel } from '../lib/workbench-state.js';
import { useWorkbenchShell } from '../composables/useWorkbenchShell.js';

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

export default {
  name: 'WorkbenchPage',
  components: {
    WorkbenchCapabilityDrawer,
    WorkbenchConversationPanel,
    WorkbenchDocumentPane,
    WorkbenchHandoffDrawer,
    WorkbenchOperationCards,
    WorkbenchReviewPanel,
    WorkbenchRunHistoryDrawer,
  },
  setup() {
    const shell = useWorkbenchShell();
    const workbenchId = computed(() => shell.detail.value?.id || null);
    const archived = computed(() => shell.detail.value?.status === 'ARCHIVED');
    const repositories = computed(() => shell.detail.value?.repositoryScope.repositories || []);
    const repositoryKeys = computed(() => repositories.value.map(
      repository => repository.repositoryKey,
    ));
    const documentPane = useWorkbenchDocumentPane({
      userId: shell.currentUserId,
      workbenchId,
      phase: shell.selectedPhase,
      repositories,
    });
    const conversationGeneration = computed(
      () => shell.selectedPhaseView.value?.conversationGeneration ?? 0,
    );
    const currentConversationId = computed(
      () => shell.selectedPhaseView.value?.currentConversation?.sessionId ?? null,
    );
    const activeRunId = computed(
      () => shell.selectedPhaseView.value?.activeRun?.runId || null,
    );
    const activeWriteRunId = computed(
      () => shell.detail.value?.activeWriteRunId || null,
    );
    const phaseStatus = computed(
      () => shell.selectedPhaseView.value?.status ?? 'NOT_STARTED',
    );
    const canCompleteSelectedPhase = computed(() =>
      shell.detail.value?.status !== 'ARCHIVED'
      && ['NOT_STARTED', 'IN_PROGRESS'].includes(
        shell.selectedPhaseView.value?.status ?? '',
      )
      && shell.selectedPhaseView.value?.activeRun == null);
    const capability = useWorkbenchCapability({
      workbenchId,
      phase: shell.selectedPhase,
    });
    const capabilityStatusLabel = computed(() => ({
      AVAILABLE: '可用',
      DEGRADED: '降级可用',
      UNAVAILABLE: '不可用',
      LOAD_FAILED: '加载失败',
      LOADING: '加载中',
      NOT_LOADED: '待加载',
    }[capability.capabilitySummaryStatus.value]));
    const capabilityStatusType = computed(() => ({
      AVAILABLE: 'success',
      DEGRADED: 'warning',
      UNAVAILABLE: 'danger',
      LOAD_FAILED: 'danger',
      LOADING: 'info',
      NOT_LOADED: 'info',
    }[capability.capabilitySummaryStatus.value]));
    const runHistory = useWorkbenchRunHistory({
      workbenchId,
      phase: shell.selectedPhase,
    });
    const handoff = useWorkbenchHandoff({
      workbenchId,
      phase: shell.selectedPhase,
      conversationGeneration,
      archived,
    });
    const review = useWorkbenchReview({
      ownerId: shell.currentUserId,
      workbenchId,
      phase: shell.selectedPhase,
      archived,
    });
    const operations = useWorkbenchOperations({
      workbenchId,
      phase: shell.selectedPhase,
      archived,
      repositories,
    });
    const expectedVersion = computed(() => shell.detail.value?.version ?? null);
    const handoffRequired = computed(
      () => shell.selectedPhase.value !== 'REQUIREMENT_ANALYSIS',
    );
    const handoffSourceVersion = computed(
      () => handoff.handoffSource.value?.reception?.sourceVersion ??
        handoff.handoffSource.value?.latestSource?.version ??
        null,
    );
    const conversation = useWorkbenchConversation({
      ownerId: shell.currentUserId,
      workbenchId,
      phase: shell.selectedPhase,
      conversationGeneration,
      currentConversationId,
      activeRunId,
      activeWriteRunId,
      expectedVersion,
      phaseStatus,
      archived,
      handoffRequired,
      handoffSourceVersion,
      reviewConfirmationId: review.reviewModifyConfirmationId,
      onConversationEnsured: applyConversationEnsure,
      onConversationRestarted: applyConversationRestart,
      onSubmitted: applyRunSubmission,
      onTerminal: reloadAfterTerminal,
    });
    const uploadedAttachments = useWorkbenchUploadedAttachments({
      workbenchId,
      phase: shell.selectedPhase,
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
    const currentPhaseLabel = computed(() => WORKBENCH_PHASES.find(
      (item) => item.phase === shell.selectedPhase.value)?.label || '阶段');

    function applyConversationEnsure(conversation) {
      if (!shell.detail.value) return;
      const phase = shell.selectedPhase.value;
      shell.detail.value = {
        ...shell.detail.value,
        version: conversation.workbenchVersion,
        phases: shell.detail.value.phases.map(item => item.phase === phase
          ? {
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
            }
          : item),
      };
    }

    function applyRunSubmission(submission, mode, attachments) {
      if (!shell.detail.value) return;
      uploadedAttachments.markSubmitted(
        (attachments || [])
          .filter(attachment => attachment.type === 'UPLOADED_CONVERSATION')
          .map(attachment => attachment.attachmentId),
      );
      const phase = shell.selectedPhase.value;
      const reviewProof = mode === 'MODIFY_WORKSPACE' && phase === 'REVIEW_REFACTOR'
        ? {
            reviewConfirmationId: review.reviewModifyConfirmationId.value,
            reviewOpinionVersion: review.reviewOpinion.value?.version ?? null,
            reviewOpinionHash: review.reviewOpinion.value?.contentHash ?? null,
          }
        : {
            reviewConfirmationId: null,
            reviewOpinionVersion: null,
            reviewOpinionHash: null,
          };
      shell.detail.value = {
        ...shell.detail.value,
        version: submission.workbenchVersion,
        activeWriteRunId: mode === 'MODIFY_WORKSPACE'
          ? submission.runId
          : shell.detail.value.activeWriteRunId,
        phases: shell.detail.value.phases.map(item => item.phase === phase
          ? {
              ...item,
              status: submission.phaseStatus,
              currentConversation: {
                sessionId: submission.sessionId,
                generation: item.conversationGeneration,
              },
              activeRun: {
                runId: submission.runId,
                runMode: mode,
                preparedAt: Date.now(),
                ...reviewProof,
              },
            }
          : item),
      };
    }

    function applyConversationRestart(restarted) {
      if (!shell.detail.value) return;
      shell.detail.value = {
        ...shell.detail.value,
        version: restarted.workbenchVersion,
        phases: shell.detail.value.phases.map(item =>
          item.currentConversation?.sessionId === restarted.previousSessionId
            ? {
                ...item,
                conversationGeneration: restarted.generation,
                currentConversation: {
                  sessionId: restarted.sessionId,
                  generation: restarted.generation,
                },
                conversationHistory: item.conversationHistory.some(
                  historical => historical.sessionId === restarted.sessionId,
                )
                  ? item.conversationHistory
                  : [...item.conversationHistory, {
                      sessionId: restarted.sessionId,
                      generation: restarted.generation,
                    }],
                activeRun: null,
              }
            : item),
      };
    }

    function reloadAfterTerminal() {
      const id = shell.detail.value?.id;
      if (id) void shell.selectWorkbench(id);
    }

    function updateComposerText(value) {
      conversation.updateComposerText(value);
      if (shell.selectedPhase.value === 'REVIEW_REFACTOR') {
        review.updateReviewText(value);
      }
    }

    async function openRunDocument(reference) {
      if (documentPane.isMobile.value) documentPane.openMobileDrawer();
      else if (documentPane.desktopMode.value === 'COLLAPSED') documentPane.restore();
      await documentPane.openDocument(reference);
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

    async function handlePhaseAdvancedCommand(command) {
      if (command === 'open-capability') {
        await capability.openCapabilityDrawer();
        return;
      }
      if (command !== 'restart-conversation') return;
      try {
        await ElMessageBox.confirm(
          '旧会话历史将只读保留，新会话不会复制任何消息',
          '确认重启阶段会话',
          {
            type: 'warning',
            confirmButtonText: '确认重启',
            cancelButtonText: '取消',
          },
        );
      } catch {
        return;
      }
      await conversation.restartConversation();
    }

    function phaseView(phase) {
      return shell.detail.value?.phases.find((item) => item.phase === phase) || null;
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

    async function openHandoffDocument(reference) {
      await openWorkbenchHandoffDocument(reference, {
        isMobile: documentPane.isMobile.value,
        desktopMode: documentPane.desktopMode.value,
        closeDrawer: handoff.closeHandoffDrawer,
        openMobileDrawer: documentPane.openMobileDrawer,
        restoreDocumentPane: documentPane.restore,
        openDocument: documentPane.openDocument,
      });
    }

    watch(
      () => conversation.composerText.value,
      value => {
        if (shell.selectedPhase.value === 'REVIEW_REFACTOR'
          && review.reviewText.value !== value) {
          review.updateReviewText(value);
        }
      },
      { flush: 'sync' },
    );

    watch(
      () => review.reviewText.value,
      value => {
        if (shell.selectedPhase.value === 'REVIEW_REFACTOR'
          && conversation.composerText.value !== value) {
          conversation.updateComposerText(value);
        }
      },
      { flush: 'sync' },
    );

    watch(
      () => conversation.runState.value?.operations
        .map(operation => operation.operationId).join('|') || '',
      fingerprint => {
        if (fingerprint) void operations.loadOperations();
      },
      { flush: 'post' },
    );

    watch(
      () => [
        shell.currentUserId.value,
        workbenchId.value ?? '',
        shell.selectedPhase.value,
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
      ...capability,
      ...handoff,
      ...review,
      ...operations,
      ...conversation,
      workbenchUploadItems: uploadedAttachments.items,
      workbenchUploadNotice: uploadedAttachments.notice,
      workbenchId,
      archived,
      repositories,
      repositoryKeys,
      currentDocumentAttachmentSelected,
      capabilityStatusLabel,
      capabilityStatusType,
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
      WORKBENCH_PHASES,
      currentPhaseLabel,
      canCompleteSelectedPhase,
      phaseStatusLabel,
      phaseView,
      workbenchStatusLabel,
      formatTime,
      toggleRepository,
      openHandoffDocument,
      updateComposerText,
      openRunDocument,
      uploadWorkbenchFiles,
      retryWorkbenchUpload,
      removeWorkbenchUpload,
      handlePhaseAdvancedCommand,
      repositoryRelativePathLabel,
    };
  },
};
</script>
