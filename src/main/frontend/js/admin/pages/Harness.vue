<template>
  <admin-shell active="harness" @ready="loadRuns">
    <template #header-actions>
      <el-button text @click="refreshSelected" :loading="loadingRuns || loadingDetail">刷新</el-button>
      <el-button type="primary" size="small" @click="openCreate" data-test="harness-create-run">
        新建 Run
      </el-button>
    </template>

    <div class="view-wrap harness-view">
      <el-alert v-if="!apiAvailable" type="warning" :closable="false" show-icon
                title="Harness Feature Flag 当前关闭"
                description="设置 AGENT_HARNESS_ENABLED=true 并重启服务后才会装配管理 API；默认关闭不会删除既有 Run、Artifact 或审计数据。">
      </el-alert>

      <template v-else>
        <el-alert type="info" :closable="false" show-icon class="harness-boundary-alert"
                  title="MVP 安全边界：仅 local、单 Codex Runtime、只读 MCP"
                  description="test / production 部署明确禁止；部署失败不会自动 rollback，重启后不确定的外部动作不会自动重放，必须人工对账。Harness 默认由 Feature Flag 关闭。">
        </el-alert>

        <div class="harness-layout">
          <el-card shadow="never" class="harness-run-panel" v-loading="loadingRuns">
            <template #header>
              <div class="harness-card-header">
                <span>Run 列表</span>
                <el-button text size="small" @click="loadRuns">刷新</el-button>
              </div>
            </template>
            <div class="harness-run-toolbar">
              <div class="harness-run-filters">
                <span v-for="opt in runFilterOptions" :key="opt.value"
                      class="harness-run-filter-chip"
                      :class="{ active: runFilter === opt.value }"
                      :data-test="'harness-run-filter-' + opt.value"
                      @click="runFilter = opt.value">{{ opt.label }}</span>
              </div>
              <el-input v-if="runSearchOpen" v-model="runSearch" size="small" clearable
                        placeholder="按标题或 Run ID 搜索"
                        data-test="harness-run-search"></el-input>
              <el-button v-else text size="small" @click="runSearchOpen = true"
                         data-test="harness-run-search-toggle">搜索</el-button>
            </div>
            <el-empty v-if="filteredRuns.length === 0" description="暂无匹配的 Harness Run"></el-empty>
            <button v-for="run in filteredRuns" :key="run.runId" type="button"
                    class="harness-run-item"
                    :class="{ selected: selectedRun && selectedRun.runId === run.runId }"
                    :data-run-id="run.runId" data-test="harness-run-row"
                    @click="loadRun(run.runId)">
              <span class="harness-run-item-row">
                <span class="harness-run-stage-dot" :class="runStageDotClass(run)"
                      :title="stageMeta(run.status).label"></span>
                <span class="harness-run-title">{{ run.title }}</span>
              </span>
              <span class="harness-run-status">{{ run.status }} · {{ run.environment }}</span>
              <span class="harness-run-id">{{ run.runId }}</span>
              <span class="harness-run-time" :title="fmtTime(run.updatedAt)">{{ fmtRelative(run.updatedAt) }}</span>
            </button>
          </el-card>

          <div class="harness-detail-panel" v-loading="loadingDetail">
            <el-empty v-if="!selectedRun" description="选择或创建一个 Run 开始"></el-empty>
            <template v-else>
              <el-card shadow="never" class="harness-summary-card">
                <div class="harness-title-row">
                  <div>
                    <h2>{{ selectedRun.title }}</h2>
                    <div class="mono-text">{{ selectedRun.runId }}</div>
                  </div>
                  <div class="harness-title-actions">
                    <el-tag>{{ selectedRun.status }}</el-tag>
                    <el-tag type="success">local</el-tag>
                    <a v-if="finalReport" :href="reportUrl()" target="_blank" rel="noopener"
                       data-test="harness-final-report">
                      <el-button type="success" size="small">最终报告</el-button>
                    </a>
                    <el-button type="danger" plain size="small" @click="cancelRun"
                               :loading="actionLoading">取消 Run</el-button>
                  </div>
                </div>

                <el-collapse class="mt16">
                  <el-collapse-item title="Run 上下文：工作目录 / Git 基线 / 定义版本" name="context">
                    <el-descriptions :column="isMobile ? 1 : 3" border size="small">
                      <el-descriptions-item label="工作目录">{{ selectedRun.workingDir }}</el-descriptions-item>
                      <el-descriptions-item label="Agent / 环境">
                        {{ selectedRun.agentType }} / {{ selectedRun.environment }}
                      </el-descriptions-item>
                      <el-descriptions-item label="定义版本">{{ selectedRun.definitionVersion }}</el-descriptions-item>
                      <el-descriptions-item label="Git 分支">
                        {{ selectedRun.workspaceBaseline.branch }}
                      </el-descriptions-item>
                      <el-descriptions-item label="Git HEAD">
                        <code :title="selectedRun.workspaceBaseline.head">
                          {{ shortHash(selectedRun.workspaceBaseline.head) }}
                        </code>
                      </el-descriptions-item>
                      <el-descriptions-item label="创建时工作树">
                        <el-tag size="small" :type="selectedRun.workspaceBaseline.clean ? 'success' : 'warning'">
                          {{ selectedRun.workspaceBaseline.clean ? '干净' : '已有脏改动' }}
                        </el-tag>
                        <code :title="selectedRun.workspaceBaseline.diffHash" style="margin-left: 6px;">
                          {{ shortHash(selectedRun.workspaceBaseline.diffHash) }}
                        </code>
                      </el-descriptions-item>
                    </el-descriptions>
                  </el-collapse-item>
                  <el-collapse-item title="冻结的原始需求（ORIGINAL_REQUIREMENT）" name="requirement">
                    <pre class="harness-pre" data-test="harness-original-requirement">{{ originalRequirement || '未找到原始需求 Artifact' }}</pre>
                  </el-collapse-item>
                </el-collapse>
              </el-card>

              <div class="harness-stepper" data-test="harness-stage-strip">
                <div v-for="(item, idx) in selectedRun.stages" :key="item.stage" class="harness-step-wrap">
                  <button type="button"
                          class="harness-step-card"
                          :class="[
                            'status-' + item.status.toLowerCase().replace(/_/g, '-'),
                            { selected: item.stage === selectedStageName }
                          ]"
                          :data-test="'harness-stage-' + item.stage.toLowerCase()"
                          @click="selectStage(item.stage)">
                    <div class="harness-step-node" :title="stageMeta(item.status).label">
                      <span class="harness-step-icon" v-html="stageStatusIcon(item.status)"></span>
                    </div>
                    <div class="harness-step-body">
                      <div class="harness-step-name">{{ stageLabel(item.stage) }}</div>
                      <div class="harness-step-status">
                        {{ stageMeta(item.status).label }}
                        <span class="harness-step-attempt" v-if="stageAttemptNumber(item)">
                          · Attempt {{ stageAttemptNumber(item) }}
                        </span>
                      </div>
                      <div class="harness-step-summary" v-if="stageSummary(item)">
                        {{ stageSummary(item) }}
                      </div>
                    </div>
                  </button>
                  <div v-if="idx < selectedRun.stages.length - 1"
                       class="harness-step-connector"
                       :class="{
                         'is-active': isConnectorActive(idx),
                         'is-invalidated': isConnectorInvalidated(idx)
                       }">
                  </div>
                </div>
              </div>

              <el-card v-if="selectedStage" shadow="never" class="harness-stage-detail">
                <div class="harness-card-header harness-stage-header">
                  <div>
                    <strong>{{ stageLabel(selectedStage.stage) }}</strong>
                    <span class="muted-text" style="margin-left: 8px;">
                      Attempt {{ selectedAttempt ? selectedAttempt.number : '-' }}
                    </span>
                  </div>
                  <div class="harness-action-row">
                    <el-button v-if="canStartStage" type="primary" size="small"
                               data-test="harness-stage-start" :loading="actionLoading"
                               @click="startStage">启动阶段</el-button>
                    <el-button v-if="canRetryStage" type="warning" plain size="small"
                               data-test="harness-stage-retry" :loading="actionLoading"
                               @click="retryStage">新 Attempt 重试</el-button>
                    <el-button v-if="canOperateRunningStage" size="small"
                               data-test="harness-ask-question" @click="openQuestion">
                      登记问题
                    </el-button>
                  </div>
                </div>

                <el-alert v-if="selectedAttempt && selectedAttempt.failureReason" type="error"
                          :closable="false" show-icon class="mt16"
                          :title="'Attempt 失败：' + selectedAttempt.failureReason">
                </el-alert>
                <el-alert v-for="failure in gateFailures" :key="failure" type="error"
                          :closable="false" show-icon class="mt16" :title="failure"
                          data-test="harness-gate-failure">
                </el-alert>

                <section class="harness-conversation" v-loading="conversationLoading"
                         data-test="harness-conversation-workspace">
                  <div class="harness-conversation-header">
                    <div class="section-title">与 Codex 协作</div>
                    <el-tag v-if="runtime" size="small" :type="runtimeStatusType(runtime.status)">
                      Codex · {{ runtime.status }}
                    </el-tag>
                  </div>

                  <div class="harness-conversation-feed" ref="conversationFeed">
                    <div v-if="stageConversationMessages.length === 0"
                         class="harness-message assistant">
                      <div class="harness-message-meta">Codex · {{ stageLabel(selectedStage.stage) }}</div>
                      <div class="harness-message-content">
                        在这里输入本阶段目标即可。系统会自动选择阶段 Skill、固化能力快照并使用本机 Codex CLI 执行。
                      </div>
                    </div>
                    <div v-for="message in stageConversationMessages" :key="message.messageId"
                         class="harness-message"
                         :class="message.role === 'USER' ? 'user' : 'assistant'"
                         :data-test="'harness-conversation-' + message.role.toLowerCase()">
                      <div class="harness-message-meta">
                        {{ message.role === 'USER' ? '你' : 'Codex' }}
                        · Attempt {{ message.attemptNumber }} · {{ fmtTime(message.createdAt) }}
                        <el-tag v-if="message.artifactType && !isArtifactMessage(message)" size="small" type="info">
                          {{ message.artifactType }}
                        </el-tag>
                      </div>
                      <!-- Artifact 消息：卡片化（可折叠 + Markdown） -->
                      <div v-if="isArtifactMessage(message)" class="harness-artifact-card"
                           :data-test="'harness-conversation-artifact-' + message.artifactType.toLowerCase()">
                        <div class="harness-artifact-header">
                          <span class="harness-artifact-icon">
                            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline></svg>
                          </span>
                          <span class="harness-artifact-type">{{ artifactTypeLabel(message) }}</span>
                          <span v-if="messageArtifact(message)" class="harness-artifact-version">v{{ messageArtifact(message).version }}</span>
                          <span class="harness-artifact-toggle"
                                @click="artifactCollapsed[message.messageId] = !artifactCollapsed[message.messageId]">
                            {{ artifactCollapsed[message.messageId] ? '展开' : '收起' }}
                          </span>
                        </div>
                        <div class="harness-artifact-body"
                             :class="{ 'is-collapsed': artifactCollapsed[message.messageId] }"
                             v-html="renderArtifactContent(message.content, message.contentType)"></div>
                        <div v-if="messageArtifact(message)" class="harness-artifact-footer">
                          <code v-if="messageArtifact(message).sha256"
                                :title="messageArtifact(message).sha256">{{ shortHash(messageArtifact(message).sha256) }}</code>
                          <a :href="artifactUrl(messageArtifact(message))" target="_blank" rel="noopener">下载原文</a>
                        </div>
                      </div>
                      <!-- 普通消息：Markdown 气泡 -->
                      <div v-else class="harness-message-content harness-message-md"
                           v-html="renderMarkdown(message.content)"></div>
                    </div>
                    <div v-if="runtimeBusy" class="harness-message assistant working"
                         data-test="harness-conversation-running">
                      <div class="harness-message-meta">Codex · Attempt {{ selectedAttempt ? selectedAttempt.number : '-' }}</div>
                      <div class="harness-message-content harness-working-line">
                        <span class="harness-working-dot"></span>
                        {{ runtimeHint }}
                      </div>
                    </div>
                  </div>

                  <div v-if="selectedStage.status === 'WAITING_INPUT'" class="harness-inline-questions">
                    <el-alert type="warning" :closable="false" show-icon
                              title="Codex 正在等待补充信息；请先回答问题，再继续修改。">
                    </el-alert>
                    <div v-for="question in unansweredQuestions"
                         :key="question.questionId" class="harness-question-card mt16"
                         data-test="harness-question">
                      <strong>{{ question.question }}</strong>
                      <div class="harness-answer-form">
                        <el-input v-model="answerDrafts[question.questionId]" type="textarea" :rows="3"
                                  placeholder="输入给 Codex 的回答"></el-input>
                        <el-button type="primary" size="small" @click="answerQuestion(question)"
                                   data-test="harness-answer-question"
                                   :loading="actionLoading">提交回答</el-button>
                      </div>
                    </div>
                  </div>

                  <div class="harness-conversation-composer">
                    <el-input v-model="conversationDraft" type="textarea" :rows="4"
                              resize="none" maxlength="20000" show-word-limit
                              data-test="harness-conversation-input"
                              :disabled="!canSendConversation || actionLoading"
                              @keydown.enter="onComposerEnter"
                              placeholder="例如：请把缓存一致性改为版本号校验，并补充并发测试。Ctrl+Enter 发送，Enter 换行。">
                    </el-input>
                    <div class="harness-composer-actions">
                      <span class="muted-text">{{ conversationHint }}</span>
                      <el-button type="primary" @click="sendConversation"
                                 data-test="harness-send-conversation"
                                 :disabled="!canSendConversation || !conversationDraft.trim()"
                                 :loading="actionLoading">
                        {{ selectedAttempt ? '发送修改并重新执行' : '发送并开始' }}
                      </el-button>
                    </div>
                  </div>

                  <div v-if="canValidateConversation || canDecideApproval"
                       class="harness-conversation-next">
                    <div class="harness-action-row">
                      <el-button v-if="canValidateConversation" type="primary" plain size="small"
                                 data-test="harness-validate-conversation"
                                 :loading="actionLoading" @click="validateAndRequestApproval">
                        校验并请求批准
                      </el-button>
                      <el-button v-if="canDecideApproval" type="success" size="small"
                                 data-test="harness-approve"
                                 @click="openApproval('approve')">批准并进入下一阶段</el-button>
                      <el-button v-if="canDecideApproval" type="danger" plain size="small"
                                 data-test="harness-reject"
                                 @click="openApproval('reject')">拒绝</el-button>
                    </div>
                  </div>
                </section>

                <div class="harness-audit-toggle" @click="auditExpanded = !auditExpanded"
                     data-test="harness-audit-toggle">
                  <span class="harness-audit-toggle-icon" :class="{ expanded: auditExpanded }">&#9654;</span>
                  <span class="harness-audit-toggle-text">
                    {{ auditExpanded ? '收起' : '展开' }}审计与高级操作
                  </span>
                  <span class="muted-text">能力快照 · 产物与审批 · 审计追踪</span>
                </div>

                <el-collapse-transition>
                  <div v-show="auditExpanded" class="harness-audit-panel">
                    <el-tabs class="harness-audit-tabs">
                      <!-- Tab 1: 能力快照 -->
                      <el-tab-pane label="能力快照">
                        <el-alert type="info" :closable="false" show-icon
                                  title="上游 Artifact 由服务端按已批准版本自动装配，客户端不能提交或覆盖正文。">
                        </el-alert>

                        <div class="harness-action-row mt16">
                          <el-button type="primary" size="small" @click="openCapabilityPanel = true"
                                     data-test="harness-resolve-snapshot"
                                     :disabled="!canOperateRunningStage || !!runtime"
                                     :loading="snapshotLoading">固化 Capability Snapshot</el-button>
                          <el-button type="success" size="small" @click="launchRuntime"
                                     data-test="harness-launch-runtime"
                                     :disabled="!canOperateRunningStage || !snapshot || !!runtime"
                                     :loading="actionLoading">启动 Codex Runtime</el-button>
                        </div>

                        <el-dialog v-model="openCapabilityPanel" title="配置并固化 Capability Snapshot"
                                   :width="isMobile ? '96%' : '720px'" :close-on-click-modal="false">
                          <el-alert type="info" :closable="false" show-icon
                                    title="这些参数用于解析阶段能力快照；快照一经固化即不可变。">
                          </el-alert>
                          <el-form label-position="top" size="small" class="mt16">
                            <el-form-item label="当前阶段输入 / 补充指令">
                              <el-input v-model="capabilityForm.currentInput" type="textarea" :rows="4"
                                        data-test="harness-current-input"
                                        placeholder="描述本 Attempt 的当前任务；已批准上游 Artifact 会由服务端自动加入 Prompt。">
                              </el-input>
                            </el-form-item>
                            <el-row :gutter="12">
                              <el-col :span="12">
                                <el-form-item label="显式 Skill ID（逗号分隔）">
                                  <el-input v-model="capabilityForm.explicitSkillIds"></el-input>
                                </el-form-item>
                              </el-col>
                              <el-col :span="12">
                                <el-form-item label="技术标签（逗号分隔）">
                                  <el-input v-model="capabilityForm.technicalTags"></el-input>
                                </el-form-item>
                              </el-col>
                            </el-row>
                            <el-form-item label="Run 级批准的 Workspace Skill ID（逗号分隔）">
                              <el-input v-model="capabilityForm.approvedWorkspaceSkillIds"></el-input>
                            </el-form-item>
                            <el-row :gutter="12">
                              <el-col :span="8">
                                <el-form-item label="可读逻辑根">
                                  <el-input v-model="capabilityForm.readableFileRoots"></el-input>
                                </el-form-item>
                              </el-col>
                              <el-col :span="8">
                                <el-form-item label="可写逻辑根">
                                  <el-input v-model="capabilityForm.writableFileRoots"></el-input>
                                </el-form-item>
                              </el-col>
                              <el-col :span="8">
                                <el-form-item label="逻辑命令白名单">
                                  <el-input v-model="capabilityForm.executableCommands"></el-input>
                                </el-form-item>
                              </el-col>
                            </el-row>
                            <el-row :gutter="12">
                              <el-col :span="8">
                                <el-form-item label="显式只读 MCP ID">
                                  <el-input v-model="capabilityForm.explicitMcpServerIds"></el-input>
                                </el-form-item>
                              </el-col>
                              <el-col :span="8">
                                <el-form-item label="必需只读 MCP ID">
                                  <el-input v-model="capabilityForm.requiredMcpServerIds"></el-input>
                                </el-form-item>
                              </el-col>
                              <el-col :span="8">
                                <el-form-item label="管理员授权 MCP ID">
                                  <el-input v-model="capabilityForm.grantedMcpServerIds"></el-input>
                                </el-form-item>
                              </el-col>
                            </el-row>
                          </el-form>
                          <template #footer>
                            <el-button @click="openCapabilityPanel = false" :disabled="snapshotLoading">取消</el-button>
                            <el-button type="primary" @click="confirmResolveSnapshot" :loading="snapshotLoading"
                                       data-test="harness-confirm-snapshot">
                              固化 Snapshot
                            </el-button>
                          </template>
                        </el-dialog>

                        <template v-if="snapshot">
                          <div class="section-title mt16">不可变 Snapshot</div>
                          <el-descriptions :column="isMobile ? 1 : 3" border size="small">
                            <el-descriptions-item label="Snapshot Hash">
                              <code :title="snapshot.snapshotHash">{{ shortHash(snapshot.snapshotHash) }}</code>
                            </el-descriptions-item>
                            <el-descriptions-item label="Prompt Hash">
                              <code :title="snapshot.promptHash">{{ shortHash(snapshot.promptHash) }}</code>
                            </el-descriptions-item>
                            <el-descriptions-item label="Prompt Pack">
                              {{ snapshot.promptPackId }}@{{ snapshot.promptPackVersion }}
                            </el-descriptions-item>
                          </el-descriptions>
                          <el-collapse class="mt16">
                            <el-collapse-item title="Skill、能力决策与最终 Prompt" name="snapshot-detail">
                              <div class="section-title">已选择 Skill</div>
                              <el-table :data="snapshot.selectedSkills || []" size="small" empty-text="未选择 Skill">
                                <el-table-column prop="id" label="ID" min-width="160"></el-table-column>
                                <el-table-column prop="version" label="版本" width="90"></el-table-column>
                                <el-table-column label="原因" min-width="130">
                                  <template #default="{ row }">{{ selectionReasonLabel(row.reason) }}</template>
                                </el-table-column>
                                <el-table-column label="Hash" min-width="140">
                                  <template #default="{ row }"><code>{{ shortHash(row.packageHash) }}</code></template>
                                </el-table-column>
                              </el-table>
                              <div class="section-title mt16">能力决策</div>
                              <el-table :data="snapshot.capabilityDecisions || []" size="small" empty-text="没有能力请求">
                                <el-table-column prop="skillId" label="Skill" min-width="140"></el-table-column>
                                <el-table-column label="能力" min-width="220">
                                  <template #default="{ row }">
                                    {{ row.request.kind }} / {{ row.request.access }} / {{ row.request.resource }}
                                  </template>
                                </el-table-column>
                                <el-table-column label="决策" min-width="130">
                                  <template #default="{ row }">
                                    <el-tag size="small" :type="row.authorized ? 'success' : 'danger'">
                                      {{ capabilityDecisionLabel(row.authorized, row.reason) }}
                                    </el-tag>
                                  </template>
                                </el-table-column>
                              </el-table>
                              <div class="section-title mt16">最终 Prompt</div>
                              <pre class="harness-pre">{{ snapshot.finalPrompt }}</pre>
                            </el-collapse-item>
                          </el-collapse>
                        </template>

                        <template v-if="runtime">
                          <div class="section-title mt16">Runtime Execution</div>
                          <el-descriptions :column="isMobile ? 1 : 3" border size="small">
                            <el-descriptions-item label="Execution ID">{{ runtime.executionId }}</el-descriptions-item>
                            <el-descriptions-item label="状态">
                              <el-tag :type="runtime.status === 'SUCCEEDED' ? 'success' : (runtime.status === 'FAILED' || runtime.status === 'LOST' ? 'danger' : 'warning')">
                                {{ runtime.status }}
                              </el-tag>
                            </el-descriptions-item>
                            <el-descriptions-item label="Runtime">{{ runtime.runtime }} {{ runtime.runtimeVersion }}</el-descriptions-item>
                            <el-descriptions-item label="Exit Code">{{ runtime.exitCode == null ? '-' : runtime.exitCode }}</el-descriptions-item>
                            <el-descriptions-item label="Evidence">{{ runtime.evidenceReference || '-' }}</el-descriptions-item>
                            <el-descriptions-item label="Cleanup">{{ runtime.cleanupStatus || '-' }}</el-descriptions-item>
                          </el-descriptions>
                          <el-alert v-if="reconciliationMessage(runtime.status)" type="warning"
                                    :closable="false" show-icon class="mt16"
                                    :title="reconciliationMessage(runtime.status)">
                          </el-alert>
                        </template>

                        <el-empty v-if="!snapshot && !runtime" description="尚未生成能力快照" size="small"></el-empty>
                      </el-tab-pane>

                      <!-- Tab 2: 产物与审批 -->
                      <el-tab-pane label="产物与审批">
                        <div class="section-title">Artifact 版本历史</div>
                        <el-table :data="stageArtifacts" size="small" empty-text="当前阶段暂无 Artifact">
                          <el-table-column prop="artifactType" label="类型" min-width="170"></el-table-column>
                          <el-table-column prop="version" label="版本" width="70"></el-table-column>
                          <el-table-column prop="attempt" label="Attempt" width="80"></el-table-column>
                          <el-table-column label="Hash" min-width="140">
                            <template #default="{ row }"><code :title="row.sha256">{{ shortHash(row.sha256) }}</code></template>
                          </el-table-column>
                          <el-table-column prop="classification" label="分级" width="100"></el-table-column>
                          <el-table-column label="操作" width="120">
                            <template #default="{ row }">
                              <el-button text type="primary" size="small" @click="previewArtifact(row)">查看</el-button>
                              <a :href="artifactUrl(row)" target="_blank" rel="noopener">
                                <el-button text type="primary" size="small">下载</el-button>
                              </a>
                            </template>
                          </el-table-column>
                        </el-table>

                        <div class="section-title mt16">当前 Attempt Gate</div>
                        <el-table :data="currentGates" size="small" empty-text="尚未执行 Gate">
                          <el-table-column prop="rule" label="规则" min-width="190"></el-table-column>
                          <el-table-column label="结果" width="90">
                            <template #default="{ row }">
                              <el-tag size="small" :type="row.passed ? 'success' : 'danger'">
                                {{ row.passed ? 'PASS' : 'FAIL' }}
                              </el-tag>
                            </template>
                          </el-table-column>
                          <el-table-column prop="reason" label="原因" min-width="240"></el-table-column>
                          <el-table-column label="时间" width="170">
                            <template #default="{ row }">{{ fmtTime(row.evaluatedAt) }}</template>
                          </el-table-column>
                        </el-table>

                        <div class="section-title mt16">Approval 历史</div>
                        <el-table :data="stageApprovals" size="small" empty-text="暂无 Approval">
                          <el-table-column prop="approvalType" label="类型" min-width="170"></el-table-column>
                          <el-table-column prop="attempt" label="Attempt" width="80"></el-table-column>
                          <el-table-column prop="decision" label="决定" width="100"></el-table-column>
                          <el-table-column label="有效" width="80">
                            <template #default="{ row }">
                              <el-tag size="small" :type="row.valid ? 'success' : 'info'">
                                {{ row.valid ? '有效' : '已失效' }}
                              </el-tag>
                            </template>
                          </el-table-column>
                          <el-table-column prop="reason" label="理由" min-width="220"></el-table-column>
                        </el-table>

                        <!-- 手动操作（逃生口）：主流程用对话区底部的「校验并请求批准」；
                             此处仅用于单独触发 Gate 或请求批准，便于排障。批准/拒绝只在对话区底部。 -->
                        <div class="harness-audit-actions">
                          <el-button size="small" text type="primary" @click="runGates"
                                     data-test="harness-run-gates" :disabled="!canOperateRunningStage"
                                     :loading="actionLoading">手动执行全部 Gate</el-button>
                          <el-button size="small" text type="primary" @click="requestApproval"
                                     data-test="harness-request-approval" :disabled="!canOperateRunningStage"
                                     :loading="actionLoading">手动请求批准</el-button>
                        </div>
                      </el-tab-pane>

                      <!-- Tab 3: 审计追踪（补充输入 + 部署 + 时间线 合并） -->
                      <el-tab-pane label="审计追踪">
                        <div class="section-title">补充输入</div>
                        <el-empty v-if="currentQuestions.length === 0" description="当前 Attempt 没有补充问题" size="small"></el-empty>
                        <div v-for="question in currentQuestions" :key="question.questionId"
                             class="harness-question-card" data-test="harness-audit-question">
                          <div class="harness-card-header">
                            <strong>{{ question.question }}</strong>
                            <el-tag size="small" :type="question.blocking ? 'warning' : 'info'">
                              {{ question.blocking ? '阻断' : '非阻断' }}
                            </el-tag>
                          </div>
                          <div class="muted-text">{{ question.questionId }} · {{ fmtTime(question.askedAt) }}</div>
                          <template v-if="question.answeredAt">
                            <div class="harness-answer">{{ question.answer }}</div>
                            <div class="muted-text">由 {{ question.answeredBy }} 回答于 {{ fmtTime(question.answeredAt) }}</div>
                          </template>
                          <div v-else class="harness-answer-form">
                            <el-input v-model="answerDrafts[question.questionId]" type="textarea" :rows="3"
                                      placeholder="输入补充答案"></el-input>
                            <el-button type="primary" size="small" @click="answerQuestion(question)"
                                       data-test="harness-audit-answer-question" :loading="actionLoading">提交回答</el-button>
                          </div>
                        </div>

                        <div class="section-title mt16">部署执行
                          <el-tag size="small" type="warning" style="margin-left: 8px;">DEPLOYMENT 阶段</el-tag>
                        </div>
                        <el-alert v-if="selectedStageName !== 'DEPLOYMENT'" type="info" :closable="false"
                                  title="切换到 DEPLOYMENT 阶段后才能执行本机部署。" size="small">
                        </el-alert>
                        <template v-else>
                          <el-alert type="warning" :closable="false" show-icon size="small"
                                    title="只允许 local 部署；test / production 被服务端拒绝；首版不自动 rollback。">
                          </el-alert>
                          <el-descriptions v-if="deploymentReadiness" :column="isMobile ? 1 : 3"
                                           border size="small" class="mt16">
                            <el-descriptions-item label="输入基线 Hash">
                              <code :title="deploymentReadiness.inputBaselineHash">
                                {{ shortHash(deploymentReadiness.inputBaselineHash) }}
                              </code>
                            </el-descriptions-item>
                            <el-descriptions-item label="Attempt">{{ deploymentReadiness.attemptNumber }}</el-descriptions-item>
                            <el-descriptions-item label="独立部署 Approval">
                              <el-tag :type="deploymentReadiness.approved ? 'success' : 'warning'">
                                {{ deploymentReadiness.approved ? '已批准' : '待批准' }}
                              </el-tag>
                            </el-descriptions-item>
                          </el-descriptions>
                          <div class="harness-action-row mt16">
                            <el-button type="warning" size="small" @click="approveDeployment"
                                       data-test="harness-deployment-approval"
                                       :disabled="!deploymentReadiness || deploymentReadiness.approved"
                                       :loading="actionLoading">独立批准 local 部署</el-button>
                            <el-button type="primary" size="small" @click="openDeployment"
                                       data-test="harness-start-deployment"
                                       :disabled="!canStartDeployment" :loading="actionLoading">
                              使用模板部署
                            </el-button>
                          </div>

                          <div class="section-title mt16">Deployment Execution 列表</div>
                          <el-table :data="deployments" size="small" empty-text="暂无部署执行">
                            <el-table-column prop="executionId" label="Execution ID" min-width="190"></el-table-column>
                            <el-table-column prop="templateId" label="模板" min-width="130"></el-table-column>
                            <el-table-column label="状态" min-width="170">
                              <template #default="{ row }">
                                <el-tag size="small" :type="deploymentStatusType(row.status)">{{ row.status }}</el-tag>
                                <div v-if="reconciliationMessage(row.status)" class="muted-text">
                                  {{ reconciliationMessage(row.status) }}
                                </div>
                              </template>
                            </el-table-column>
                            <el-table-column prop="failureReason" label="失败原因" min-width="220"></el-table-column>
                            <el-table-column label="操作" width="100">
                              <template #default="{ row }">
                                <el-button v-if="row.status === 'RECONCILIATION_REQUIRED'" text type="warning"
                                           size="small" data-test="harness-reconcile-deployment"
                                           @click="reconcileDeployment(row)">人工对账</el-button>
                              </template>
                            </el-table-column>
                          </el-table>
                        </template>

                        <div class="section-title mt16">事件时间线</div>
                        <el-timeline>
                          <el-timeline-item v-for="event in events" :key="event.sequence"
                                            :timestamp="fmtTime(event.occurredAt)" placement="top">
                            <div class="harness-event-title">
                              #{{ event.sequence }} {{ event.eventType }}
                              <el-tag v-if="event.stage" size="small" type="info">{{ event.stage }}</el-tag>
                            </div>
                            <div class="muted-text">{{ event.actor }} · {{ event.detail }}</div>
                          </el-timeline-item>
                        </el-timeline>
                      </el-tab-pane>
                    </el-tabs>
                  </div>
                </el-collapse-transition>
              </el-card>
            </template>
          </div>
        </div>
        </template>
      </div>

      <el-dialog v-model="createOpen" title="新建 Harness Run"
                 :width="isMobile ? '96%' : '720px'" :close-on-click-modal="false">
        <el-alert type="info" :closable="false" show-icon
                  title="原始需求创建后立即冻结为 ORIGINAL_REQUIREMENT Artifact；后续阶段只能消费服务端批准版本。">
        </el-alert>
        <el-form label-position="top" size="small" class="mt16">
          <el-form-item label="标题">
            <el-input v-model="createForm.title" data-test="harness-create-title"></el-input>
          </el-form-item>
          <el-form-item label="Git 工作目录">
            <el-autocomplete v-model="createForm.workingDir" style="width: 100%"
                             :fetch-suggestions="queryWorkingDir"
                             data-test="harness-create-working-dir"
                             placeholder="可从历史 Run 联想，或手动输入绝对路径"></el-autocomplete>
          </el-form-item>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="Runtime">
                <el-input v-model="createForm.agentType" disabled></el-input>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="环境">
                <el-input v-model="createForm.environment" disabled></el-input>
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item>
            <template #label>
              <span>原始需求</span>
              <el-button text type="primary" size="small" style="margin-left: 8px;"
                         data-test="harness-insert-template"
                         @click="insertRequirementTemplate">插入需求模板</el-button>
            </template>
            <el-input v-model="createForm.originalRequirement" type="textarea" :rows="8"
                      data-test="harness-create-requirement"
                      placeholder="建议包含背景、目标、验收标准、范围。可点击「插入需求模板」快速开始。"></el-input>
            <div class="muted-text" style="font-size: 12px; margin-top: 4px;">
              原始需求创建后立即冻结为不可变 Artifact，后续阶段只能消费服务端批准版本。
            </div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="createOpen = false" :disabled="actionLoading">取消</el-button>
          <el-button type="primary" @click="createRun" :loading="actionLoading"
                     data-test="harness-submit-create">创建</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="previewOpen" :title="previewTitle || 'Artifact 正文'"
                 :width="isMobile ? '96%' : '720px'" :close-on-click-modal="false">
        <div v-loading="previewLoading" class="harness-artifact-preview"
             v-html="renderArtifactContent(previewContent, previewContentType)"></div>
        <template #footer>
          <el-button @click="previewOpen = false">关闭</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="approvalOpen"
                 :title="approvalDecision === 'approve' ? '批准当前 Artifact 基线' : '拒绝当前 Artifact 基线'"
                 :width="isMobile ? '96%' : '560px'" :close-on-click-modal="false">
        <div v-if="selectedStage" class="mono-text">
          {{ selectedStage.stage }} · {{ selectedStage.artifactBaselineHash }}
        </div>
        <div v-loading="approvalArtifactLoading" v-if="approvalArtifactContent"
             class="harness-artifact-preview mt16"
             v-html="renderArtifactContent(approvalArtifactContent, approvalArtifactContentType)"></div>
        <el-form label-position="top" size="small" class="mt16">
          <el-form-item label="审批理由">
            <el-input v-model="approvalForm.reason" type="textarea" :rows="4"></el-input>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="approvalOpen = false" :disabled="actionLoading">取消</el-button>
          <el-button :type="approvalDecision === 'approve' ? 'success' : 'danger'"
                     @click="submitApproval" :loading="actionLoading"
                     data-test="harness-submit-approval">提交</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="questionOpen" title="登记当前 Attempt 补充问题"
                 :width="isMobile ? '96%' : '620px'" :close-on-click-modal="false">
        <el-form label-position="top" size="small">
          <el-form-item label="问题 ID">
            <el-input v-model="questionForm.questionId"></el-input>
          </el-form-item>
          <el-form-item label="问题">
            <el-input v-model="questionForm.question" type="textarea" :rows="4"></el-input>
          </el-form-item>
          <el-form-item label="阻断当前 Attempt">
            <el-switch v-model="questionForm.blocking"></el-switch>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="questionOpen = false" :disabled="actionLoading">取消</el-button>
          <el-button type="primary" @click="submitQuestion" :loading="actionLoading">提交</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="deploymentOpen" title="执行受控 local 部署"
                 :width="isMobile ? '96%' : '620px'" :close-on-click-modal="false">
        <el-alert type="warning" :closable="false" show-icon
                  title="命令只能来自管理员注册的模板；页面不接受任意 Shell。">
        </el-alert>
        <el-form label-position="top" size="small" class="mt16">
          <el-form-item label="模板 ID">
            <el-input v-model="deploymentForm.templateId" data-test="harness-deployment-template"></el-input>
          </el-form-item>
          <el-form-item label="已批准输入基线 Hash">
            <el-input :model-value="deploymentReadiness ? deploymentReadiness.inputBaselineHash : ''" disabled></el-input>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="deploymentOpen = false" :disabled="actionLoading">取消</el-button>
          <el-button type="primary" @click="startDeployment" :loading="actionLoading"
                     data-test="harness-submit-deployment">开始部署</el-button>
        </template>
      </el-dialog>
    </admin-shell>
  </template>

<script>
import * as HarnessAdminUtils from '../harness-utils.js';
import { renderMarkdown as renderMarkdownFn, escapeHtml } from '../../lib/formatters.js';
import { ref, reactive, computed, nextTick, onBeforeUnmount } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';

export default {
  setup() {
    const stageNames = ['ANALYSIS', 'DESIGN', 'IMPLEMENTATION', 'DEPLOYMENT'];
    const stageLabels = {
      ANALYSIS: '需求分析',
      DESIGN: '方案设计',
      IMPLEMENTATION: 'TDD 实现',
      DEPLOYMENT: '部署验证'
    };
    const runSummaries = ref([]);
    const selectedRun = ref(null);
    const selectedStageName = ref('ANALYSIS');
    const events = ref([]);
    const deployments = ref([]);
    const deploymentReadiness = ref(null);
    const snapshot = ref(null);
    const runtime = ref(null);
    // 当前已加载资源的 stage#attempt 键，用于区分"阶段切换"与"同阶段轮询刷新"
    const loadedStageKey = ref('');
    const conversationMessages = ref([]);
    const conversationDraft = ref('');
    const conversationNonce = ref('');
    const conversationFeed = ref(null);
    const conversationLoading = ref(false);
    const originalRequirement = ref('');
    const apiAvailable = ref(true);
    const loadingRuns = ref(false);
    const loadingDetail = ref(false);
    const actionLoading = ref(false);
    const snapshotLoading = ref(false);
    const isMobile = ref(window.innerWidth <= 768);
    const createOpen = ref(false);
    const approvalOpen = ref(false);
    const approvalDecision = ref('approve');
    const questionOpen = ref(false);
    const deploymentOpen = ref(false);
    const createNonce = ref('');
    const answerDrafts = reactive({});

    const createForm = reactive({
      title: '',
      workingDir: '',
      originalRequirement: '',
      agentType: 'CODEX',
      environment: 'local',
      definitionVersion: 'harness@1.0.0'
    });
    const capabilityForm = reactive({
      explicitSkillIds: '',
      technicalTags: 'java',
      approvedWorkspaceSkillIds: '',
      readableFileRoots: 'workspace',
      writableFileRoots: '',
      executableCommands: '',
      explicitMcpServerIds: '',
      requiredMcpServerIds: '',
      grantedMcpServerIds: '',
      currentInput: ''
    });
    const approvalForm = reactive({ reason: '' });
    // 产物 Tab artifact 正文预览
    const previewOpen = ref(false);
    const previewContent = ref('');
    const previewContentType = ref('');
    const previewTitle = ref('');
    const previewLoading = ref(false);
    // 审批对话框展示待审批 artifact 正文
    const approvalArtifactContent = ref('');
    const approvalArtifactContentType = ref('');
    const approvalArtifactLoading = ref(false);
    const questionForm = reactive({ questionId: '', question: '', blocking: true });
    const deploymentForm = reactive({ templateId: 'local-default' });
    const auditExpanded = ref(false);
    const openCapabilityPanel = ref(false);
    // Run 列表筛选 / 搜索
    const runFilter = ref('all'); // all / running / waiting / done / failed
    const runFilterOptions = [
      { value: 'all', label: '全部' },
      { value: 'running', label: '进行中' },
      { value: 'waiting', label: '待审批' },
      { value: 'done', label: '已完成' },
      { value: 'failed', label: '失败' }
    ];
    const runSearch = ref('');
    const runSearchOpen = ref(false);
    // Artifact 消息卡片折叠态（messageId -> 是否折叠）
    const artifactCollapsed = reactive({});

    window.addEventListener('resize', () => { isMobile.value = window.innerWidth <= 768; });

    const selectedStage = computed(() => {
      const stages = selectedRun.value && Array.isArray(selectedRun.value.stages)
        ? selectedRun.value.stages : [];
      return stages.find(item => item.stage === selectedStageName.value) || null;
    });
    const selectedAttempt = computed(() => HarnessAdminUtils.currentAttempt(selectedStage.value));
    const currentGates = computed(() => {
      if (!selectedRun.value || !selectedAttempt.value) {
        return [];
      }
      return (selectedRun.value.gateResults || []).filter(item =>
        item.stage === selectedStageName.value
          && Number(item.attempt) === Number(selectedAttempt.value.number));
    });
    const gateFailures = computed(() => {
      if (!selectedRun.value || !selectedAttempt.value) {
        return [];
      }
      return HarnessAdminUtils.gateFailureSummary(
        selectedRun.value.gateResults, selectedStageName.value, selectedAttempt.value.number);
    });
    const stageArtifacts = computed(() => {
      if (!selectedRun.value) {
        return [];
      }
      return (selectedRun.value.artifacts || [])
        .filter(item => item.stage === selectedStageName.value)
        .slice()
        .sort((left, right) => right.version - left.version || right.createdAt - left.createdAt);
    });
    const stageApprovals = computed(() => {
      if (!selectedRun.value) {
        return [];
      }
      return (selectedRun.value.approvals || [])
        .filter(item => item.stage === selectedStageName.value)
        .slice()
        .reverse();
    });
    const currentQuestions = computed(() => {
      if (!selectedRun.value || !selectedAttempt.value) {
        return [];
      }
      return (selectedRun.value.questions || []).filter(item =>
        item.stage === selectedStageName.value
          && Number(item.attempt) === Number(selectedAttempt.value.number));
    });
    const unansweredQuestions = computed(() => currentQuestions.value.filter(
      item => !item.answeredAt));
    const stageConversationMessages = computed(() => conversationMessages.value.filter(
      item => item.stage === selectedStageName.value));
    // Run 列表：按 updatedAt 倒序 + 状态筛选 + 关键字搜索
    const filteredRuns = computed(() => {
      const keyword = runSearch.value.trim().toLowerCase();
      const filtered = runSummaries.value.filter(run => {
        if (keyword) {
          const hay = ((run.title || '') + ' ' + (run.runId || '')).toLowerCase();
          if (!hay.includes(keyword)) {
            return false;
          }
        }
        if (runFilter.value === 'all') {
          return true;
        }
        return runBucket(run) === runFilter.value;
      });
      return filtered.slice().sort((left, right) => {
        const ta = left.updatedAt ? new Date(left.updatedAt).getTime() : 0;
        const tb = right.updatedAt ? new Date(right.updatedAt).getTime() : 0;
        return tb - ta;
      });
    });
    const workingDirSuggestions = computed(() => {
      const set = new Set();
      runSummaries.value.forEach(run => {
        if (run && run.workingDir) {
          set.add(run.workingDir);
        }
      });
      return Array.from(set);
    });
    const runtimeBusy = computed(() => HarnessAdminUtils.runtimeBusy(runtime.value));
    const canSendConversation = computed(() => HarnessAdminUtils.canSendConversation(
      selectedStage.value, runtime.value));
    const canValidateConversation = computed(() => selectedStage.value
      && selectedStage.value.status === 'RUNNING'
      && runtime.value && runtime.value.status === 'SUCCEEDED');
    const conversationHint = computed(() => {
      if (selectedStage.value && selectedStage.value.status === 'WAITING_INPUT') {
        return '请先回答 Codex 的阻断问题';
      }
      if (runtimeBusy.value) {
        return '当前 Runtime 执行中，完成后可继续修改';
      }
      if (!canSendConversation.value) {
        return '当前 Run 或阶段已不可修改';
      }
      return '系统自动使用阶段默认 Skill 与本机 Codex CLI';
    });
    // 执行中状态文案：按阶段给出更具体的提示，替代单一固定文案
    const runtimeHint = computed(() => {
      const stage = selectedStageName.value;
      const hints = {
        ANALYSIS: '正在读取上下文并分析需求、产出可验收标准…',
        DESIGN: '正在设计方案、追踪矩阵与变更点…',
        IMPLEMENTATION: '正在按 TDD 编写代码与测试，留下 RED/GREEN 证据…',
        DEPLOYMENT: '正在执行本机 local 部署并采集证据…'
      };
      return hints[stage] || '正在读取上下文并执行本阶段任务，结果会自动回到这里…';
    });
    const finalReport = computed(() => latestArtifact('FINAL_REPORT'));
    const canStartStage = computed(() => selectedStage.value && selectedStage.value.status === 'PENDING');
    const canRetryStage = computed(() => selectedStage.value
      && ['FAILED', 'PASSED', 'INVALIDATED'].includes(selectedStage.value.status));
    const canOperateRunningStage = computed(() => selectedStage.value
      && selectedStage.value.status === 'RUNNING');
    const canDecideApproval = computed(() => selectedStage.value
      && selectedStage.value.status === 'WAITING_APPROVAL'
      && Boolean(selectedStage.value.artifactBaselineHash));
    const canStartDeployment = computed(() => selectedRun.value
      && HarnessAdminUtils.canStartDeployment(selectedRun.value));

    function csv(value) {
      return String(value || '').split(',').map(item => item.trim()).filter(Boolean);
    }

    function runUrl(runId) {
      return '/api/harness/runs/' + encodeURIComponent(runId);
    }

    function stageUrl(stage) {
      if (!selectedRun.value) {
        throw new Error('请先选择 Run');
      }
      return runUrl(selectedRun.value.runId) + '/stages/' + encodeURIComponent(stage);
    }

    function randomToken() {
      if (window.crypto && typeof window.crypto.randomUUID === 'function') {
        return window.crypto.randomUUID();
      }
      return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
    }

    function idempotencyKey(identity) {
      const storageKey = 'harness-command:' + identity;
      let value = window.sessionStorage.getItem(storageKey);
      if (!value) {
        value = 'harness-ui-' + randomToken();
        window.sessionStorage.setItem(storageKey, value);
      }
      return value;
    }

    async function api(path, options) {
      const response = await fetch(path, options || {});
      const text = await response.text();
      let body = {};
      if (text) {
        try {
          body = JSON.parse(text);
        } catch (ignored) {
          body = text;
        }
      }
      if (!response.ok) {
        const message = body && typeof body === 'object'
          ? (body.message || body.error || body.code) : body;
        const error = new Error(message || ('HTTP ' + response.status));
        error.status = response.status;
        error.body = body;
        throw error;
      }
      return body;
    }

    async function optionalApi(path) {
      try {
        return await api(path);
      } catch (error) {
        if (error.status === 404) {
          return null;
        }
        throw error;
      }
    }

    async function post(path, payload, identity) {
      const headers = {};
      if (payload !== undefined) {
        headers['Content-Type'] = 'application/json';
      }
      if (identity) {
        headers['Idempotency-Key'] = idempotencyKey(identity);
      }
      return api(path, {
        method: 'POST',
        headers,
        body: payload === undefined ? undefined : JSON.stringify(payload)
      });
    }

    function showError(prefix, error) {
      ElMessage.error(prefix + '：' + (error.message || error));
    }

    async function loadRuns(preferredRunId) {
      loadingRuns.value = true;
      try {
        const values = await api('/api/harness/runs');
        apiAvailable.value = true;
        runSummaries.value = Array.isArray(values) ? values : [];
        const targetId = preferredRunId
          || (selectedRun.value && selectedRun.value.runId)
          || (runSummaries.value[0] && runSummaries.value[0].runId);
        if (targetId) {
          await loadRun(targetId);
        } else {
          clearSelection();
        }
      } catch (error) {
        if (!HarnessAdminUtils.harnessApiAvailable(error.status, error.body)) {
          apiAvailable.value = false;
          clearSelection();
        } else {
          showError('加载 Harness Run 失败', error);
        }
      } finally {
        loadingRuns.value = false;
      }
    }

    function clearSelection() {
      selectedRun.value = null;
      events.value = [];
      deployments.value = [];
      deploymentReadiness.value = null;
      snapshot.value = null;
      runtime.value = null;
      loadedStageKey.value = '';
      conversationMessages.value = [];
      conversationDraft.value = '';
      originalRequirement.value = '';
    }

    async function loadRun(runId) {
      loadingDetail.value = true;
      try {
        const base = runUrl(runId);
        const values = await Promise.all([
          api(base),
          api(base + '/events'),
          api(base + '/deployments')
        ]);
        selectedRun.value = values[0];
        // 切换 Run 时重置已加载键，强制 loadStageResources 重新干净加载新 Run 的资源
        loadedStageKey.value = '';
        events.value = Array.isArray(values[1]) ? values[1] : [];
        deployments.value = Array.isArray(values[2]) ? values[2] : [];
        if (!selectedRun.value.stages.some(item => item.stage === selectedStageName.value)) {
          selectedStageName.value = 'ANALYSIS';
        }
        if (!conversationNonce.value) {
          conversationNonce.value = randomToken();
        }
        await Promise.all([loadOriginalRequirement(), loadConversation(), loadStageResources()]);
      } catch (error) {
        showError('加载 Run 详情失败', error);
      } finally {
        loadingDetail.value = false;
      }
    }

    async function refreshSelected() {
      if (selectedRun.value) {
        await loadRun(selectedRun.value.runId);
        const summaries = await api('/api/harness/runs');
        runSummaries.value = Array.isArray(summaries) ? summaries : [];
      } else {
        await loadRuns();
      }
    }

    async function loadOriginalRequirement() {
      originalRequirement.value = '';
      const artifact = latestArtifact('ORIGINAL_REQUIREMENT');
      if (!artifact || !selectedRun.value) {
        return;
      }
      try {
        const response = await fetch(HarnessAdminUtils.artifactDownloadUrl(
          selectedRun.value.runId, artifact.artifactId));
        if (!response.ok) {
          throw new Error('HTTP ' + response.status);
        }
        originalRequirement.value = await response.text();
      } catch (error) {
        originalRequirement.value = '原始需求读取失败：' + (error.message || error);
      }
    }

    async function selectStage(stage) {
      selectedStageName.value = stage;
      conversationNonce.value = randomToken();
      await loadStageResources();
      scrollConversationToEnd();
    }

    async function loadConversation() {
      if (!selectedRun.value) {
        conversationMessages.value = [];
        return;
      }
      conversationLoading.value = true;
      try {
        const values = await api(runUrl(selectedRun.value.runId) + '/conversation');
        conversationMessages.value = Array.isArray(values) ? values : [];
        scrollConversationToEnd();
      } finally {
        conversationLoading.value = false;
      }
    }

    function scrollConversationToEnd() {
      nextTick(() => {
        if (conversationFeed.value) {
          conversationFeed.value.scrollTop = conversationFeed.value.scrollHeight;
        }
      });
    }

    async function loadStageResources() {
      const run = selectedRun.value;
      const stage = selectedStage.value;
      const attempt = HarnessAdminUtils.currentAttempt(stage);
      if (!run || !stage || !attempt) {
        loadedStageKey.value = '';
        snapshot.value = null;
        runtime.value = null;
        deploymentReadiness.value = null;
        return;
      }
      const stageKey = stage.stage + '#' + attempt.number;
      // 仅在阶段/Attempt 真正切换时清空旧值。轮询每 2s 调一次本函数，
      // 若每次都先清 runtime，会让 runtimeBusy 短暂变 false，导致工作指示器、
      // 输入框可用态、conversationHint 每 2s 闪烁。
      if (loadedStageKey.value !== stageKey) {
        snapshot.value = null;
        runtime.value = null;
        deploymentReadiness.value = null;
      }
      loadedStageKey.value = stageKey;
      const base = stageUrl(stage.stage) + '/attempts/' + attempt.number;
      const selectedStageAtStart = stage.stage;
      const values = await Promise.all([
        optionalApi(base + '/capability-snapshot'),
        optionalApi(base + '/execution'),
        stage.stage === 'DEPLOYMENT' && stage.status === 'RUNNING'
          ? optionalApi(stageUrl('DEPLOYMENT') + '/deployment-readiness')
          : Promise.resolve(null)
      ]);
      if (selectedStageName.value === selectedStageAtStart) {
        snapshot.value = values[0];
        runtime.value = values[1];
        deploymentReadiness.value = values[2];
      }
    }

    function latestArtifact(type) {
      if (!selectedRun.value) {
        return null;
      }
      return (selectedRun.value.artifacts || [])
        .filter(item => item.artifactType === type)
        .sort((left, right) => right.version - left.version)[0] || null;
    }

    function openCreate() {
      Object.assign(createForm, {
        title: '',
        workingDir: '',
        originalRequirement: '',
        agentType: 'CODEX',
        environment: 'local',
        definitionVersion: 'harness@1.0.0'
      });
      createNonce.value = randomToken();
      createOpen.value = true;
    }

    async function createRun() {
      if (!createForm.title.trim() || !createForm.workingDir.trim()
          || !createForm.originalRequirement.trim()) {
        ElMessage.warning('标题、工作目录和原始需求不能为空');
        return;
      }
      actionLoading.value = true;
      try {
        const result = await post('/api/harness/runs', {
          title: createForm.title.trim(),
          workingDir: createForm.workingDir.trim(),
          originalRequirement: createForm.originalRequirement,
          agentType: 'CODEX',
          environment: 'local',
          definitionVersion: createForm.definitionVersion
        }, 'create:' + createNonce.value);
        createOpen.value = false;
        ElMessage.success('Harness Run 已创建');
        await loadRuns(result.runId);
        scrollSelectedRunIntoView();
      } catch (error) {
        showError('创建 Run 失败', error);
      } finally {
        actionLoading.value = false;
      }
    }

    async function startStage() {
      await runAction('启动阶段', async () => {
        const stage = selectedStageName.value;
        await post(stageUrl(stage) + '/start', undefined,
          'start:' + selectedRun.value.runId + ':' + stage);
      });
    }

    async function retryStage() {
      try {
        await ElMessageBox.confirm(
          '重试会创建新的不可变 Attempt，并使当前阶段及下游旧结果失效。',
          '确认重试', { type: 'warning' });
      } catch (error) {
        return;
      }
      await runAction('重试阶段', async () => {
        const stage = selectedStageName.value;
        const attempt = selectedAttempt.value;
        await post(stageUrl(stage) + '/retry', undefined,
          'retry:' + selectedRun.value.runId + ':' + stage + ':' + (attempt ? attempt.number : 0));
      });
    }

    async function cancelRun() {
      let prompt;
      try {
        prompt = await ElMessageBox.prompt(
          '取消只停止当前 Run/Runtime；不会自动 rollback 或重放外部动作。',
          '取消 Run', { inputPlaceholder: '请输入取消原因', inputValidator: value => Boolean(value && value.trim()) });
      } catch (error) {
        return;
      }
      await runAction('取消 Run', () => post(runUrl(selectedRun.value.runId) + '/cancel', {
        reason: prompt.value.trim()
      }));
    }

    async function resolveSnapshot() {
      // 兼容旧入口（已迁移到对话框 confirmResolveSnapshot）
      await confirmResolveSnapshot();
    }

    async function confirmResolveSnapshot() {
      if (!capabilityForm.currentInput.trim()) {
        ElMessage.warning('当前阶段输入不能为空');
        return;
      }
      snapshotLoading.value = true;
      try {
        snapshot.value = await post(stageUrl(selectedStageName.value) + '/capability-snapshot', {
          explicitSkillIds: csv(capabilityForm.explicitSkillIds),
          technicalTags: csv(capabilityForm.technicalTags),
          approvedWorkspaceSkillIds: csv(capabilityForm.approvedWorkspaceSkillIds),
          readableFileRoots: csv(capabilityForm.readableFileRoots),
          writableFileRoots: csv(capabilityForm.writableFileRoots),
          executableCommands: csv(capabilityForm.executableCommands),
          explicitMcpServerIds: csv(capabilityForm.explicitMcpServerIds),
          requiredMcpServerIds: csv(capabilityForm.requiredMcpServerIds),
          grantedMcpServerIds: csv(capabilityForm.grantedMcpServerIds),
          currentInput: capabilityForm.currentInput
        });
        openCapabilityPanel.value = false;
        ElMessage.success('Capability Snapshot 已固化');
      } catch (error) {
        showError('固化 Snapshot 失败', error);
      } finally {
        snapshotLoading.value = false;
      }
    }

    async function launchRuntime() {
      await runAction('启动 Runtime', async () => {
        const stage = selectedStageName.value;
        const attempt = selectedAttempt.value;
        await post(stageUrl(stage) + '/executions', undefined,
          'runtime:' + selectedRun.value.runId + ':' + stage + ':' + attempt.number);
      });
    }

    async function sendConversation() {
      const message = conversationDraft.value.trim();
      if (!message || !canSendConversation.value || !selectedRun.value) {
        return;
      }
      const runId = selectedRun.value.runId;
      const stage = selectedStageName.value;
      const nonce = conversationNonce.value || randomToken();
      conversationNonce.value = nonce;
      actionLoading.value = true;
      try {
        await post(stageUrl(stage) + '/conversation', { message },
          'conversation:' + runId + ':' + stage + ':' + nonce);
        conversationDraft.value = '';
        conversationNonce.value = randomToken();
        ElMessage.success('修改意见已发送，Codex Runtime 已启动');
        await loadRun(runId);
      } catch (error) {
        showError('发送修改意见失败', error);
      } finally {
        actionLoading.value = false;
      }
    }

    async function runGates() {
      const rules = selectedStage.value ? selectedStage.value.deterministicGates || [] : [];
      if (rules.length === 0) {
        ElMessage.warning('当前阶段没有确定性 Gate');
        return;
      }
      await runAction('执行 Gate', async () => {
        for (const rule of rules) {
          await post(stageUrl(selectedStageName.value) + '/gates', { rule });
        }
      });
    }

    async function requestApproval() {
      await runAction('请求阶段批准', () =>
        post(stageUrl(selectedStageName.value) + '/request-approval'));
    }

    async function validateAndRequestApproval() {
      if (!canValidateConversation.value || !selectedStage.value) {
        return;
      }
      actionLoading.value = true;
      try {
        const rules = selectedStage.value.deterministicGates || [];
        for (const rule of rules) {
          await post(stageUrl(selectedStageName.value) + '/gates', { rule });
        }
        await post(stageUrl(selectedStageName.value) + '/request-approval');
        ElMessage.success('确定性校验通过，已进入待批准状态');
        await refreshSelected();
      } catch (error) {
        showError('校验并请求批准失败', error);
        await refreshSelected();
      } finally {
        actionLoading.value = false;
      }
    }

    function openApproval(decision) {
      if (!selectedStage.value || !selectedStage.value.artifactBaselineHash) {
        ElMessage.warning('当前阶段尚未生成待审批 Artifact 基线');
        return;
      }
      approvalDecision.value = decision;
      approvalForm.reason = '';
      approvalOpen.value = true;
      loadApprovalArtifact();
    }

    // 加载当前阶段最新 artifact 正文,供审批对话框展示
    async function loadApprovalArtifact() {
      approvalArtifactContent.value = '';
      approvalArtifactContentType.value = '';
      const artifact = stageArtifacts.value[0];
      if (!artifact || !selectedRun.value) return;
      approvalArtifactLoading.value = true;
      try {
        const response = await fetch(artifactUrl(artifact));
        if (!response.ok) throw new Error('HTTP ' + response.status);
        approvalArtifactContent.value = await response.text();
        approvalArtifactContentType.value = artifact.contentType || '';
      } catch (error) {
        approvalArtifactContent.value = '待审批 Artifact 读取失败：' + (error.message || error);
        approvalArtifactContentType.value = 'text/plain';
      } finally {
        approvalArtifactLoading.value = false;
      }
    }

    // 产物 Tab 点"查看"弹出 artifact 正文预览
    async function previewArtifact(artifact) {
      if (!artifact || !selectedRun.value) return;
      previewOpen.value = true;
      previewLoading.value = true;
      previewContent.value = '';
      previewContentType.value = artifact.contentType || '';
      previewTitle.value = (artifact.artifactType || 'Artifact') + ' v' + (artifact.version || '?');
      try {
        const response = await fetch(artifactUrl(artifact));
        if (!response.ok) throw new Error('HTTP ' + response.status);
        previewContent.value = await response.text();
      } catch (error) {
        previewContent.value = '读取失败：' + (error.message || error);
        previewContentType.value = 'text/plain';
      } finally {
        previewLoading.value = false;
      }
    }

    async function submitApproval() {
      if (!approvalForm.reason.trim()) {
        ElMessage.warning('审批理由不能为空');
        return;
      }
      const decision = approvalDecision.value;
      await runAction(decision === 'approve' ? '批准阶段' : '拒绝阶段', async () => {
        const stage = selectedStageName.value;
        const hash = selectedStage.value.artifactBaselineHash;
        await post(stageUrl(stage) + '/' + decision, {
          artifactBaselineHash: hash,
          reason: approvalForm.reason.trim()
        }, decision + ':' + selectedRun.value.runId + ':' + stage + ':' + hash);
        approvalOpen.value = false;
      });
    }

    function openQuestion() {
      Object.assign(questionForm, {
        questionId: 'question-' + Date.now(),
        question: '',
        blocking: true
      });
      questionOpen.value = true;
    }

    async function submitQuestion() {
      if (!questionForm.questionId.trim() || !questionForm.question.trim()) {
        ElMessage.warning('问题 ID 和问题内容不能为空');
        return;
      }
      await runAction('登记补充问题', async () => {
        await post(stageUrl(selectedStageName.value) + '/questions', {
          questionId: questionForm.questionId.trim(),
          question: questionForm.question.trim(),
          blocking: questionForm.blocking
        });
        questionOpen.value = false;
      });
    }

    async function answerQuestion(question) {
      const answer = String(answerDrafts[question.questionId] || '').trim();
      if (!answer) {
        ElMessage.warning('回答不能为空');
        return;
      }
      await runAction('回答补充问题', async () => {
        await post(runUrl(selectedRun.value.runId) + '/questions/'
          + encodeURIComponent(question.questionId) + '/answer', { answer });
        answerDrafts[question.questionId] = '';
      });
    }

    async function approveDeployment() {
      if (!deploymentReadiness.value) {
        ElMessage.warning('当前部署输入基线尚未就绪');
        return;
      }
      let prompt;
      try {
        prompt = await ElMessageBox.prompt(
          '该批准仅授权当前 Hash 的一次 local 部署动作，不代表最终交付批准。',
          '批准 local 部署', { inputPlaceholder: '请输入部署批准理由', inputValidator: value => Boolean(value && value.trim()) });
      } catch (error) {
        return;
      }
      await runAction('批准 local 部署', () => {
        const hash = deploymentReadiness.value.inputBaselineHash;
        return post(stageUrl('DEPLOYMENT') + '/deployment-approval', {
          inputBaselineHash: hash,
          reason: prompt.value.trim()
        }, 'deployment-approval:' + selectedRun.value.runId + ':'
          + deploymentReadiness.value.attemptNumber + ':' + hash);
      });
    }

    function openDeployment() {
      deploymentForm.templateId = 'local-default';
      deploymentOpen.value = true;
    }

    async function startDeployment() {
      if (!deploymentForm.templateId.trim() || !deploymentReadiness.value) {
        ElMessage.warning('部署模板和已批准输入基线不能为空');
        return;
      }
      await runAction('执行 local 部署', async () => {
        const hash = deploymentReadiness.value.inputBaselineHash;
        await post(stageUrl('DEPLOYMENT') + '/deployments', {
          templateId: deploymentForm.templateId.trim(),
          approvedInputBaselineHash: hash
        }, 'deployment:' + selectedRun.value.runId + ':'
          + deploymentReadiness.value.attemptNumber + ':' + hash + ':' + deploymentForm.templateId.trim());
        deploymentOpen.value = false;
      });
    }

    async function reconcileDeployment(execution) {
      let prompt;
      try {
        prompt = await ElMessageBox.prompt(
          '只在人工确认部署未成功后执行；该动作会将不确定执行对账为失败。',
          '人工对账', { inputPlaceholder: '请输入人工核查证据/原因', inputValidator: value => Boolean(value && value.trim()) });
      } catch (error) {
        return;
      }
      await runAction('部署人工对账', () =>
        post(runUrl(selectedRun.value.runId) + '/deployments/'
          + encodeURIComponent(execution.executionId) + '/reconcile', {
          reason: prompt.value.trim()
        }));
    }

    async function runAction(label, operation) {
      actionLoading.value = true;
      try {
        await operation();
        ElMessage.success(label + '已受理');
        await refreshSelected();
      } catch (error) {
        showError(label + '失败', error);
      } finally {
        actionLoading.value = false;
      }
    }

    function stageMeta(status) {
      return HarnessAdminUtils.stageStatusMeta(status);
    }

    // Markdown 渲染（复用主站 formatters.js：marked + DOMPurify 净化）
    function renderMarkdown(text) {
      return renderMarkdownFn(text || '');
    }

    // Artifact 正文按 contentType 渲染:JSON pretty print、纯文本 pre、Markdown 渲染
    function renderArtifactContent(content, contentType) {
      const text = String(content || '');
      const escape = escapeHtml;
      if (contentType === 'application/json') {
        try {
          return '<pre class="harness-artifact-json">' + escape(JSON.stringify(JSON.parse(text), null, 2)) + '</pre>';
        } catch (e) {
          return '<pre class="harness-artifact-plain">' + escape(text) + '</pre>';
        }
      }
      if (contentType === 'text/plain') {
        return '<pre class="harness-artifact-plain">' + escape(text) + '</pre>';
      }
      return renderMarkdown(text);
    }

    // Artifact 消息的类型文案与是否渲染为卡片
    const artifactCardTypes = ['REQUIREMENT', 'DESIGN_DOC', 'IMPLEMENTATION_SUMMARY',
      'FINAL_REPORT', 'ORIGINAL_REQUIREMENT'];
    const artifactTypeLabels = {
      REQUIREMENT: '需求基线',
      DESIGN_DOC: '方案设计',
      IMPLEMENTATION_SUMMARY: '实现总结',
      FINAL_REPORT: '最终报告',
      ORIGINAL_REQUIREMENT: '原始需求'
    };
    function isArtifactMessage(message) {
      return Boolean(message && artifactCardTypes.includes(message.artifactType));
    }
    function artifactTypeLabel(message) {
      return artifactTypeLabels[message && message.artifactType] || (message && message.artifactType) || '产物';
    }
    // 消息只带 artifactType 标签；从当前 Run 的 artifacts 里按 stage+attempt+type 反查完整产物（hash/version/下载）
    function messageArtifact(message) {
      if (!selectedRun.value || !message || !message.artifactType) {
        return null;
      }
      return (selectedRun.value.artifacts || [])
        .filter(a => a.stage === message.stage
          && Number(a.attempt) === Number(message.attemptNumber)
          && a.artifactType === message.artifactType)
        .sort((left, right) => right.version - left.version)[0] || null;
    }

    function stageLabel(stage) {
      return stageLabels[stage] || stage;
    }

    // Stepper 状态图标（用 SVG/Unicode 字符，避免额外依赖）
    const stageStatusIcons = {
      PASSED: '<svg viewBox="0 0 24 24" width="16" height="16"><polyline points="5,12 10,17 19,7" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/></svg>',
      RUNNING: '<span class="harness-stepper-dot"></span>',
      WAITING_APPROVAL: '<svg viewBox="0 0 24 24" width="16" height="16"><path d="M12 2 L22 20 L2 20 Z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><line x1="12" y1="10" x2="12" y2="14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><circle cx="12" cy="17" r="1" fill="currentColor"/></svg>',
      WAITING_INPUT: '<svg viewBox="0 0 24 24" width="16" height="16"><circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="2"/><text x="12" y="16" text-anchor="middle" font-size="12" font-weight="700" fill="currentColor">?</text></svg>',
      FAILED: '<svg viewBox="0 0 24 24" width="16" height="16"><line x1="6" y1="6" x2="18" y2="18" stroke="currentColor" stroke-width="3" stroke-linecap="round"/><line x1="18" y1="6" x2="6" y2="18" stroke="currentColor" stroke-width="3" stroke-linecap="round"/></svg>',
      INVALIDATED: '<svg viewBox="0 0 24 24" width="16" height="16"><circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="2" stroke-dasharray="4 3"/><line x1="7" y1="7" x2="17" y2="17" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
      PENDING: '',
      CANCELLED: '<svg viewBox="0 0 24 24" width="14" height="14"><rect x="5" y="5" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"/></svg>',
      CANCELLING: '<span class="harness-stepper-dot"></span>'
    };

    function stageStatusIcon(status) {
      return stageStatusIcons[status] || '';
    }

    function stageAttemptNumber(stage) {
      if (!stage || !Array.isArray(stage.attempts) || stage.attempts.length === 0) {
        return null;
      }
      return stage.attempts[stage.attempts.length - 1].number;
    }

    // Stepper 连接线状态：当前阶段及之前为实线激活
    function isConnectorActive(index) {
      const stages = selectedRun.value ? selectedRun.value.stages : [];
      if (!stages.length) return false;
      const currentIdx = stages.findIndex(s => s.stage === selectedStageName.value);
      // 索引小于当前选中阶段的连接线为激活
      return index < currentIdx;
    }

    // Stepper 连接线失效：下游阶段 INVALIDATED 时连接线变虚线
    function isConnectorInvalidated(index) {
      const stages = selectedRun.value ? selectedRun.value.stages : [];
      if (!stages.length || index + 1 >= stages.length) return false;
      const nextStage = stages[index + 1];
      return nextStage.status === 'INVALIDATED';
    }

    // 阶段产物摘要：从该阶段最新已批准 Artifact 中提取关键指标
    function stageSummary(stage) {
      if (!stage || !selectedRun.value) return '';
      const artifacts = (selectedRun.value.artifacts || [])
        .filter(a => a.stage === stage.stage && a.classification === 'APPROVED');
      if (artifacts.length === 0) return '';
      // 优先取主产物做摘要
      const summaryArtifact = artifacts.find(a =>
        a.artifactType === 'REQUIREMENT'
        || a.artifactType === 'DESIGN_DOC'
        || a.artifactType === 'IMPLEMENTATION_SUMMARY');
      if (!summaryArtifact) return '';
      // 从 contentSize 或 version 推断信息密度（MVP 阶段先用版本号+类型提示）
      const typeLabel = {
        REQUIREMENT: '需求基线',
        DESIGN_DOC: '方案基线',
        IMPLEMENTATION_SUMMARY: '实现基线'
      }[summaryArtifact.artifactType] || summaryArtifact.artifactType;
      return typeLabel + ' v' + summaryArtifact.version;
    }

    function fmtTime(value) {
      if (!value) {
        return '-';
      }
      const date = typeof value === 'number' ? new Date(value) : new Date(String(value));
      return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false });
    }

    // 相对时间（"2 小时前"），hover 由 title 显示绝对时间
    function fmtRelative(value) {
      if (!value) {
        return '-';
      }
      const date = typeof value === 'number' ? new Date(value) : new Date(String(value));
      if (Number.isNaN(date.getTime())) {
        return String(value);
      }
      const diff = Date.now() - date.getTime();
      if (diff < 0) {
        return fmtTime(value);
      }
      const sec = Math.floor(diff / 1000);
      if (sec < 60) {
        return '刚刚';
      }
      const min = Math.floor(sec / 60);
      if (min < 60) {
        return min + ' 分钟前';
      }
      const hr = Math.floor(min / 60);
      if (hr < 24) {
        return hr + ' 小时前';
      }
      const day = Math.floor(hr / 24);
      if (day < 30) {
        return day + ' 天前';
      }
      return fmtTime(value);
    }

    // Run 归类到筛选桶（防御式：summary 无 stages 时退回 run.status）
    function runBucket(run) {
      const status = String((run && run.status) || '').toUpperCase();
      if (status === 'COMPLETED') {
        return 'done';
      }
      if (status === 'FAILED' || status === 'CANCELLED') {
        return 'failed';
      }
      const stages = run && Array.isArray(run.stages) ? run.stages : [];
      if (stages.some(s => ['WAITING_APPROVAL', 'WAITING_INPUT'].includes(s.status))) {
        return 'waiting';
      }
      if (stages.some(s => ['RUNNING', 'CANCELLING'].includes(s.status)) || status === 'RUNNING') {
        return 'running';
      }
      return 'all';
    }

    // Run 列表条目左侧的"当前阶段"小圆点颜色
    function runStageDotClass(run) {
      const stages = run && Array.isArray(run.stages) ? run.stages : [];
      const active = stages.find(s => s.status !== 'PASSED' && s.status !== 'CANCELLED');
      const status = active ? active.status : ((run && run.status) || '');
      if (['WAITING_APPROVAL', 'WAITING_INPUT'].includes(status)) {
        return 'is-waiting';
      }
      if (['RUNNING', 'CANCELLING'].includes(status)) {
        return 'is-running';
      }
      if (status === 'FAILED') {
        return 'is-failed';
      }
      if (status === 'INVALIDATED') {
        return 'is-invalidated';
      }
      if (status === 'PASSED' || (run && run.status) === 'COMPLETED') {
        return 'is-passed';
      }
      return '';
    }

    // 新建 Run 原始需求结构化模板
    const requirementTemplate = '## 背景\n\n<描述问题背景与动机>\n\n## 目标\n\n<本 Run 要达成的可观测目标>\n\n## 验收标准\n\n- AC-1: <可验证的验收条件>\n- AC-2: <可验证的验收条件>\n\n## 范围\n\n- 包含: <本 Run 范围内的事项>\n- 不包含: <明确排除的事项>';
    function insertRequirementTemplate() {
      createForm.originalRequirement = requirementTemplate;
    }

    // 工作目录自动联想（从历史 Run 的工作目录中过滤）
    function queryWorkingDir(queryString, callback) {
      const suggestions = workingDirSuggestions.value;
      const keyword = (queryString || '').toLowerCase();
      const results = keyword
        ? suggestions.filter(value => value.toLowerCase().includes(keyword))
        : suggestions;
      callback(results.map(value => ({ value })));
    }

    // 新建 Run 后自动滚动到左侧选中项
    function scrollSelectedRunIntoView() {
      nextTick(() => {
        const el = document.querySelector('.harness-run-item.selected');
        if (el && el.scrollIntoView) {
          el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        }
      });
    }

    // 对话输入框：Ctrl/Cmd+Enter 发送，普通 Enter 换行
    function onComposerEnter(event) {
      if (event.ctrlKey || event.metaKey) {
        event.preventDefault();
        sendConversation();
      }
    }

    // 全局数字键 1/2/3/4 切换阶段（焦点在输入框时不响应）
    function onGlobalKeydown(event) {
      if (!selectedRun.value) {
        return;
      }
      const target = event.target;
      const tag = target && target.tagName;
      if (['INPUT', 'TEXTAREA', 'SELECT'].includes(tag) || (target && target.isContentEditable)) {
        return;
      }
      if (event.ctrlKey || event.metaKey || event.altKey) {
        return;
      }
      const map = { '1': 'ANALYSIS', '2': 'DESIGN', '3': 'IMPLEMENTATION', '4': 'DEPLOYMENT' };
      const stage = map[event.key];
      if (!stage) {
        return;
      }
      const stages = selectedRun.value.stages || [];
      if (stages.some(s => s.stage === stage)) {
        event.preventDefault();
        selectStage(stage);
      }
    }
    window.addEventListener('keydown', onGlobalKeydown);

    function deploymentStatusType(status) {
      return {
        PREPARED: 'info',
        RUNNING: 'warning',
        SUCCEEDED: 'success',
        FAILED: 'danger',
        RECONCILIATION_REQUIRED: 'warning'
      }[status] || 'info';
    }

    function runtimeStatusType(status) {
      return {
        SUCCEEDED: 'success',
        FAILED: 'danger',
        TIMED_OUT: 'danger',
        LOST: 'danger',
        CANCELLED: 'info'
      }[status] || 'warning';
    }

    function artifactUrl(artifact) {
      return selectedRun.value
        ? HarnessAdminUtils.artifactDownloadUrl(selectedRun.value.runId, artifact.artifactId) : '#';
    }

    function reportUrl() {
      return selectedRun.value ? runUrl(selectedRun.value.runId) + '/report' : '#';
    }

    let runtimePollInFlight = false;
    const runtimePollTimer = window.setInterval(async () => {
      if (runtimePollInFlight || !runtimeBusy.value || !selectedRun.value) {
        return;
      }
      runtimePollInFlight = true;
      const runId = selectedRun.value.runId;
      try {
        const base = runUrl(runId);
        const values = await Promise.all([api(base), api(base + '/conversation')]);
        if (!selectedRun.value || selectedRun.value.runId !== runId) {
          return;
        }
        const nextRun = values[0];
        const prevRun = selectedRun.value;
        // 仅在 run 实质变化（updatedAt/status）时才替换 selectedRun 与消息列表，
        // 避免每 2s 全量替换触发 el-descriptions/el-table/v-html 重渲染造成抖动。
        // runtime 状态由 loadStageResources 每 tick 单独刷新，不受此影响。
        if (!prevRun || prevRun.updatedAt !== nextRun.updatedAt
            || prevRun.status !== nextRun.status) {
          selectedRun.value = nextRun;
          conversationMessages.value = Array.isArray(values[1]) ? values[1] : [];
        }
        await loadStageResources();
        scrollConversationToEnd();
      } catch (error) {
        showError('刷新 Codex 执行状态失败', error);
      } finally {
        runtimePollInFlight = false;
      }
    }, 2000);
    onBeforeUnmount(() => {
      window.clearInterval(runtimePollTimer);
      window.removeEventListener('keydown', onGlobalKeydown);
    });

    return {
      stageNames,
      stageLabels,
      runSummaries,
      selectedRun,
      selectedStageName,
      selectedStage,
      selectedAttempt,
      events,
      deployments,
      deploymentReadiness,
      snapshot,
      runtime,
      conversationMessages,
      conversationDraft,
      conversationFeed,
      conversationLoading,
      originalRequirement,
      apiAvailable,
      loadingRuns,
      loadingDetail,
      actionLoading,
      snapshotLoading,
      isMobile,
      createOpen,
      approvalOpen,
      approvalDecision,
      questionOpen,
      deploymentOpen,
      createForm,
      capabilityForm,
      approvalForm,
      previewOpen,
      previewContent,
      previewContentType,
      previewTitle,
      previewLoading,
      approvalArtifactContent,
      approvalArtifactContentType,
      approvalArtifactLoading,
      questionForm,
      deploymentForm,
      auditExpanded,
      openCapabilityPanel,
      answerDrafts,
      runFilter,
      runFilterOptions,
      runSearch,
      runSearchOpen,
      artifactCollapsed,
      filteredRuns,
      workingDirSuggestions,
      currentGates,
      gateFailures,
      stageArtifacts,
      stageApprovals,
      currentQuestions,
      unansweredQuestions,
      stageConversationMessages,
      runtimeBusy,
      canSendConversation,
      canValidateConversation,
      conversationHint,
      runtimeHint,
      finalReport,
      canStartStage,
      canRetryStage,
      canOperateRunningStage,
      canDecideApproval,
      canStartDeployment,
      loadRuns,
      loadRun,
      refreshSelected,
      selectStage,
      openCreate,
      createRun,
      startStage,
      retryStage,
      cancelRun,
      resolveSnapshot,
      confirmResolveSnapshot,
      launchRuntime,
      sendConversation,
      runGates,
      requestApproval,
      validateAndRequestApproval,
      openApproval,
      previewArtifact,
      submitApproval,
      openQuestion,
      submitQuestion,
      answerQuestion,
      approveDeployment,
      openDeployment,
      startDeployment,
      reconcileDeployment,
      stageMeta,
      stageLabel,
      stageStatusIcon,
      stageAttemptNumber,
      isConnectorActive,
      isConnectorInvalidated,
      stageSummary,
      fmtTime,
      fmtRelative,
      renderMarkdown,
      renderArtifactContent,
      isArtifactMessage,
      artifactTypeLabel,
      messageArtifact,
      runStageDotClass,
      insertRequirementTemplate,
      queryWorkingDir,
      onComposerEnter,
      deploymentStatusType,
      runtimeStatusType,
      artifactUrl,
      reportUrl,
      selectionReasonLabel: HarnessAdminUtils.selectionReasonLabel,
      rejectionReasonLabel: HarnessAdminUtils.rejectionReasonLabel,
      capabilityDecisionLabel: HarnessAdminUtils.capabilityDecisionLabel,
      shortHash: HarnessAdminUtils.shortHash,
      reconciliationMessage: HarnessAdminUtils.reconciliationMessage
    };
  }
};
</script>