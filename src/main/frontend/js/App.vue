<template>
  <el-container style="height: 100%">
    <!-- ========== 顶栏 ========== -->
    <el-header height="52px" style="padding: 0;">
      <div class="topbar">
        <!-- ① 汉堡菜单（移动端） -->
        <el-button v-if="isMobile" text class="hamburger-btn" @click="sidebarVisible = !sidebarVisible">
          <el-icon size="20"><fold /></el-icon>
        </el-button>
        <!-- ① Logo -->
        <span class="logo">Q & A</span>
        <div class="divider hidden-mobile"></div>

        <!-- ②.5 Agent 选择 -->
        <el-radio-group v-model="agentType" size="small" :disabled="!!activeSessionId" :class="['hidden-mobile', { 'locked-radio-group': !!activeSessionId }]" style="flex-shrink: 0;" @change="onAgentTypeChange">
          <el-radio-button value="CODEX">
            <span :style="{ fontWeight: 700, fontSize: '12px' }">Codex</span>
          </el-radio-button>
          <el-radio-button value="CLAUDE">
            <span :style="{ fontWeight: 700, fontSize: '12px' }">Claude</span>
          </el-radio-button>
        </el-radio-group>
        <div class="divider hidden-mobile"></div>

        <!-- ③ 工作目录选择器 -->
        <div class="workspace-selector" @click="openWorkspaceDialog">
          <el-icon><folder-opened /></el-icon>
          <span class="path">{{ currentPath || '选择工作目录' }}</span>
          <el-icon><arrow-down /></el-icon>
        </div>

        <!-- ④ 分支标签 -->
          <el-popover v-model:visible="branchPopoverVisible" trigger="click" width="400" placement="bottom-start">
            <template #reference>
              <el-tag
:type="currentBranch ? 'success' : 'warning'"
                      :effect="currentBranch ? 'light' : 'dark'"
                      :class="{ 'branch-tag-attention': !currentBranch }"
                      style="cursor: pointer; flex-shrink: 0;">
                🌿 {{ currentBranch || '请选择分支' }}
              </el-tag>
            </template>
            <!-- 分支切换内容 -->
            <div style="display: flex; align-items: center; gap: 8px;">
              <el-select
v-model="selectedBranch" filterable allow-create default-first-option
                         placeholder="选择或输入分支名" style="flex: 1;" clearable
                         :teleported="false" @clear="clearBranch">
                <el-option v-for="b in branchOptions" :key="b" :label="b" :value="b"></el-option>
              </el-select>
              <el-button
type="primary" size="default" :loading="switchingBranch"
                         :disabled="!selectedBranch || !currentPath" @click="switchBranch">
                切换
              </el-button>
              <el-button
size="default" :loading="updatingBranch"
                         :disabled="!currentBranch" @click="updateBranch">
                更新
              </el-button>
            </div>
            <div v-if="savedBranches.length > 0" style="margin-top: 8px;">
              <el-tag
v-for="b in savedBranches" :key="b" closable size="small"
                      :type="b === currentBranch ? 'success' : 'info'"
                      :effect="b === selectedBranch ? 'dark' : 'light'"
                      :disabled="removingBranch === b"
                      :style="{ margin: '2px', cursor: 'pointer', outline: b === selectedBranch ? '2px solid #409eff' : 'none' }"
                      @close="removeSavedBranch(b)"
                      @click="selectedBranch = b">
                <template v-if="removingBranch === b">
                  <el-icon class="is-loading" style="margin-right: 4px;"><loading /></el-icon>
                </template>
                {{ b }}
              </el-tag>
            </div>
            <div v-if="switchResult && switchResult.filter(r => r.created).length > 0" style="margin-top: 8px; font-size: 12px; color: #909399; max-height: 260px; overflow-y: auto;">
              <div v-for="r in switchResult.filter(r => r.created)" :key="r.name">
                <el-icon style="color: #67c23a;"><circle-check-filled /></el-icon>
                {{ r.name }}
                <span v-if="r.actualBranch && r.actualBranch !== selectedBranch" style="color: #e6a23c;">(master)</span>
              </div>
            </div>
            <div v-if="updateResult && updateResult.length > 0" style="margin-top: 8px; font-size: 12px; color: #909399; max-height: 260px; overflow-y: auto;">
              <div v-for="r in updateResult" :key="'upd-' + r.name">
                <el-icon v-if="r.updated" style="color: #67c23a;"><circle-check-filled /></el-icon>
                <el-icon v-else-if="r.skipped" style="color: #909399;"><circle-check-filled /></el-icon>
                <el-icon v-else style="color: #f56c6c;"><circle-close-filled /></el-icon>
                {{ r.name }}
                <span :style="{ color: r.updated ? '#67c23a' : (r.skipped ? '#909399' : '#f56c6c') }">
                  {{ r.reason }}
                </span>
              </div>
            </div>
          </el-popover>

        <!-- 右侧弹性空间 -->
        <span style="flex: 1;"></span>

        <!-- ⑤ 使用说明 -->
        <el-popover trigger="click" :width="460" placement="bottom-end">
          <template #reference>
            <el-button style="flex-shrink: 0;" plain>
              <el-icon><question-filled /></el-icon>
              <span class="hidden-mobile">使用说明</span>
            </el-button>
          </template>
          <div style="font-size: 13px; line-height: 1.7; max-height: 70vh; overflow-y: auto;">
            <div style="font-weight: 600; color: #303133; margin-bottom: 6px;">快速开始</div>
            <ol style="padding-left: 20px; margin: 0 0 10px;">
              <li>（可选）点击顶部<b>工作目录</b>，选择项目根路径</li>
              <li>（可选）点击 🌿 <b>分支标签</b> 切换或更新 worktree</li>
              <li>在底部输入框提问，<el-tag size="small" effect="plain">Enter</el-tag> 发送，<el-tag size="small" effect="plain">Ctrl+Enter</el-tag> 换行</li>
            </ol>
            <div style="font-weight: 600; color: #303133; margin-bottom: 6px;">输入技巧</div>
            <ul style="padding-left: 20px; margin: 0 0 10px;">
              <li>输入 <code>/</code> 触发命令补全，<el-tag size="small" effect="plain">↑↓</el-tag> 选择，<el-tag size="small" effect="plain">Tab</el-tag> 确认</li>
              <li><b>图片</b>：点击「图片」按钮上传，或直接 <el-tag size="small" effect="plain">Ctrl+V</el-tag> 粘贴截图，路径自动追加进消息</li>
              <li><b>附件</b>：点击「附件」按钮上传文本类文件，以 <code>[附件清单]</code> 形式追加进消息供 AI 用 <code>Read</code> 工具读取分析</li>
              <li><b>清除上下文</b>：开启新一轮对话但保留会话</li>
              <li><b>停止</b>：中断当前 AI 回答</li>
            </ul>
            <div style="font-weight: 600; color: #303133; margin-bottom: 6px;">侧边栏 / 历史</div>
            <ul style="padding-left: 20px; margin: 0 0 10px;">
              <li>历史列表展示<b>所有用户</b>的对话，可查看 / 继续他人的会话</li>
              <li>点击历史项 <b>查看</b>；点击 ▶ <b>继续对话</b>；🗑 <b>删除</b>（仅能删除自己创建的对话，他人会话不显示删除按钮）</li>
            </ul>
            <div style="font-weight: 600; color: #303133; margin-bottom: 6px;">分享 / 协作</div>
            <ul style="padding-left: 20px; margin: 0 0 10px;">
              <li>会话头条或历史详情抽屉的 <b>分享</b> 按钮可生成只读公开链接，他人<b>免登录</b>即可查看，但不能续聊或启动 Agent</li>
            </ul>
            <div style="font-weight: 600; color: #303133; margin-bottom: 6px;">其他</div>
            <ul style="padding-left: 20px; margin: 0;">
              <li><b>工作空间弹窗</b>支持 上传 / 下载 / 删除 文件</li>
            </ul>
          </div>
        </el-popover>

        <!-- ⑤-b 建议/反馈 (已移除) -->

        <!-- ⑥~⑨ 顶栏工具按钮组: 靠右成组, 窄屏自动收起文字只留图标, 避免挤掉按钮 -->
        <div class="topbar-actions">
        <!-- 召回历史已迁移至管理后台「召回历史」页(/admin/refinery.html);此处只保留聊天内的召回开关 -->

        <!-- ⑧ 定时任务按钮 -->
        <el-button v-if="canUseScheduledTask" style="flex-shrink: 0;" @click="taskManagerVisible = true">
          <el-icon><timer /></el-icon>
          <span class="hidden-mobile">定时任务</span>
          <el-badge v-if="taskList.length" :value="taskList.length" :offset="[6, -2]" />
        </el-button>

        <!-- ⑨ 登出按钮（仅在开启鉴权时显示） -->
        <el-button v-if="authEnabled" style="flex-shrink: 0;" plain @click="doLogout">
          <el-icon><switch-button /></el-icon>
          <span class="hidden-mobile">登出</span>
        </el-button>
        </div>
      </div>
    </el-header>

    <el-container style="flex: 1; overflow: hidden;">
      <!-- ========== 侧边栏 - 桌面端 ========== -->
      <el-aside v-if="!isMobile" width="280px">
        <!-- 新对话按钮 -->
        <div class="sidebar-header">
          <el-button type="primary" :loading="starting" :disabled="!currentPath" style="width: 100%;" @click="newConversation">
            <el-icon><plus /></el-icon>
            <span>新对话</span>
          </el-button>
        </div>

        <!-- 历史对话列表（按日期分组，占满剩余空间） -->
        <div class="history-list" @scroll="onHistoryScroll">
          <div v-for="(group, gi) in groupedHistory" :key="group.label">
            <div class="history-group-title" style="display: flex; align-items: center; justify-content: space-between;">
              <span>{{ group.label }}</span>
              <el-button v-if="gi === 0" size="small" text :loading="historyLoading" title="刷新列表" style="padding: 0; height: auto; min-height: 0;" @click="loadHistory(true)">
                <el-icon><refresh /></el-icon>
              </el-button>
            </div>
            <div v-for="h in group.items" :key="h.sessionId" class="history-item" style="display: flex; align-items: center;">
              <div style="flex: 1; min-width: 0;" @click="viewHistory(h.sessionId)">
                <div class="history-title">
                  {{ h.title || '新对话' }}
                  <el-tag v-if="h.running" size="small" type="warning" effect="plain">运行中</el-tag>
                </div>
                <div class="history-meta">
                  <span v-if="h.agentType" :class="'agent-tag agent-tag-' + h.agentType.toLowerCase()">{{ h.agentType }}</span>
                  {{ h.workingDir.split('/').pop() }} · {{ h.messageCount }} 条 · {{ formatTime(h.createdAt) }}
                </div>
              </div>
              <el-button size="small" text type="primary" style="flex-shrink: 0; margin-left: 4px;" title="继续对话" @click.stop="resumeHistory(h)">
                <el-icon><video-play /></el-icon>
              </el-button>
              <el-button v-if="canDelete(h)" size="small" text type="danger" style="flex-shrink: 0; margin-left: 4px;" @click.stop="deleteHistory(h.sessionId)">
                <el-icon><delete /></el-icon>
              </el-button>
            </div>
          </div>
          <div v-if="historyLoading" style="text-align: center; padding: 8px; color: #909399; font-size: 12px;">加载中...</div>
          <div v-else-if="!historyHasMore && historyList.length > 0" style="text-align: center; padding: 8px; color: #c0c4cc; font-size: 12px;">没有更多了</div>
          <el-empty v-if="historyList.length === 0 && !historyLoading" description="暂无历史记录" :image-size="40"></el-empty>
        </div>
      </el-aside>

      <!-- ========== 侧边栏 - 移动端抽屉 ========== -->
      <el-drawer v-if="isMobile" v-model="sidebarVisible" direction="ltr" size="280px" :show-close="false" :with-header="false">
        <div style="display: flex; flex-direction: column; height: 100%;">
          <!-- Agent 切换（移动端放侧边栏） -->
          <div style="padding: 12px 16px; border-bottom: 1px solid #f0f0f0;">
            <div style="font-size: 12px; color: #909399; margin-bottom: 8px;">Agent {{ activeSessionId ? '（会话进行中）' : '' }}</div>
            <el-radio-group v-model="agentType" size="small" :disabled="!!activeSessionId" :class="{ 'locked-radio-group': !!activeSessionId }" @change="onAgentTypeChange">
              <el-radio-button value="CODEX">
                <span :style="{ fontWeight: 700, fontSize: '12px' }">Codex</span>
              </el-radio-button>
              <el-radio-button value="CLAUDE">
                <span :style="{ fontWeight: 700, fontSize: '12px' }">Claude</span>
              </el-radio-button>
            </el-radio-group>
          </div>
          <!-- 新对话按钮 -->
          <div class="sidebar-header">
            <el-button type="primary" :loading="starting" :disabled="!currentPath" style="width: 100%;" @click="newConversation(); sidebarVisible = false;">
              <el-icon><plus /></el-icon>
              <span>新对话</span>
            </el-button>
          </div>
          <!-- 历史列表 -->
          <div class="history-list" style="flex: 1; min-height: 0; overflow-y: auto;" @scroll="onHistoryScroll">
            <div v-for="(group, gi) in groupedHistory" :key="group.label">
              <div class="history-group-title" style="display: flex; align-items: center; justify-content: space-between;">
                <span>{{ group.label }}</span>
                <el-button v-if="gi === 0" size="small" text :loading="historyLoading" title="刷新列表" style="padding: 0; height: auto; min-height: 0;" @click="loadHistory(true)">
                  <el-icon><refresh /></el-icon>
                </el-button>
              </div>
              <div v-for="h in group.items" :key="h.sessionId" class="history-item" style="display: flex; align-items: center;">
                <div style="flex: 1; min-width: 0;" @click="viewHistory(h.sessionId); sidebarVisible = false;">
                  <div class="history-title">
                    {{ h.title || '新对话' }}
                    <el-tag v-if="h.running" size="small" type="warning" effect="plain">运行中</el-tag>
                  </div>
                  <div class="history-meta">
                    {{ h.workingDir.split('/').pop() }} · {{ h.messageCount }} 条 · {{ formatTime(h.createdAt) }}
                  </div>
                </div>
                <el-button size="small" text type="primary" style="flex-shrink: 0; margin-left: 4px;" title="继续对话" @click.stop="resumeHistory(h); sidebarVisible = false;">
                  <el-icon><video-play /></el-icon>
                </el-button>
                <el-button v-if="canDelete(h)" size="small" text type="danger" style="flex-shrink: 0; margin-left: 4px;" @click.stop="deleteHistory(h.sessionId)">
                  <el-icon><delete /></el-icon>
                </el-button>
              </div>
            </div>
            <div v-if="historyLoading" style="text-align: center; padding: 8px; color: #909399; font-size: 12px;">加载中...</div>
            <div v-else-if="!historyHasMore && historyList.length > 0" style="text-align: center; padding: 8px; color: #c0c4cc; font-size: 12px;">没有更多了</div>
            <el-empty v-if="historyList.length === 0 && !historyLoading" description="暂无历史记录" :image-size="40"></el-empty>
          </div>
        </div>
      </el-drawer>

      <!-- ========== 主内容区 ========== -->
      <el-main style="padding: 0;">
        <!-- 聊天闭环抽成 ChatPanel 组件(window.ChatPanel),主控台/admin 共享同一实现 -->
        <chat-panel
          :working-dir="currentPath"
          :agent-type="agentType"
          :initial-session-id="activeSessionId"
          :initial-resume-id="activeResumeId"
          :rag-enabled="chatRagEnabled"
          @session-created="onSessionCreated"
          @refresh-history="onRefreshHistory"></chat-panel>
      </el-main>
    </el-container>

    <!-- ========== 工作空间弹窗 ========== -->
    <el-dialog
v-model="workspaceDialogVisible" title="工作空间" :width="previewVisible ? (isMobile ? '92%' : '80%') : (isMobile ? '92%' : '520px')"
               data-test="workspace-dialog">
      <el-form label-position="top" size="default">
        <el-form-item label="根路径">
          <el-select v-model="selectedRoot" placeholder="选择根路径" style="width: 100%;" @change="handleRootChange">
            <el-option v-for="root in roots" :key="root" :label="root" :value="root"></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="当前路径">
          <el-input v-model="workspaceCandidatePath" readonly data-test="workspace-candidate-path">
            <template #prefix>
              <el-icon><folder-opened /></el-icon>
            </template>
          </el-input>
        </el-form-item>
      </el-form>

      <!-- 文件/目录列表 -->
      <el-scrollbar v-if="!previewVisible" height="280px">
        <div
v-for="item in folderList" :key="item.path" class="fs-item"
             data-test="fs-row" :data-name="item.name || item.path"
             @click="item.dir ? loadList(item.path) : null">
          <el-icon v-if="item.dir"><folder /></el-icon>
          <el-icon v-else><document /></el-icon>
          <span class="fs-name">{{ item.name || item.path }}</span>
          <span v-if="!item.dir" style="color:#909399;font-size:12px;margin-right:8px;">{{ formatSize(item.size) }}</span>
          <span v-if="!item.dir && item.name !== '..'" class="fs-actions">
            <el-dropdown trigger="click" @command="handleFileCommand($event, item)" @click.stop>
              <el-icon style="cursor:pointer; font-size: 16px; color: #909399;" @click.stop><more-filled /></el-icon>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="isMarkdown(item.name)" command="preview">预览</el-dropdown-item>
                  <el-dropdown-item command="download">下载</el-dropdown-item>
                  <el-dropdown-item command="delete" style="color:#f56c6c;">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </span>
        </div>
        <el-empty v-if="folderList.length === 0" description="空目录" :image-size="60"></el-empty>
      </el-scrollbar>

      <!-- md 文件预览视图:复用 text-segment 排版 + renderMarkdown 渲染 -->
      <div v-if="previewVisible" data-test="md-preview">
        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; gap:8px;">
          <span style="font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">{{ previewTitle }}</span>
          <el-button size="small" @click="closePreview">返回</el-button>
        </div>
        <el-scrollbar height="380px">
          <div v-loading="previewLoading" class="md-preview-body md-body" v-html="previewHtml"></div>
        </el-scrollbar>
      </div>

      <!-- 上传按钮 -->
      <div v-if="!previewVisible" style="margin-top: 12px;">
        <el-upload
          data-test="fs-upload"
          :action="'/api/fs/upload?path=' + encodeURIComponent(workspaceCandidatePath)"
          name="file"
          :show-file-list="false"
          :on-success="onUploadSuccess"
          :on-error="onUploadError"
          multiple>
          <el-button type="primary" size="small" style="width: 100%;">
            <el-icon><upload /></el-icon>
            <span>上传文件</span>
          </el-button>
        </el-upload>
      </div>

      <template #footer>
        <el-button @click="workspaceDialogVisible = false">取消</el-button>
        <el-button type="primary" data-test="workspace-confirm" @click="confirmWorkspace">确认</el-button>
      </template>
    </el-dialog>

    <!-- ========== 定时任务管理弹窗 ========== -->
    <el-dialog v-model="taskManagerVisible" title="定时任务管理" :width="isMobile ? '92%' : '600px'">
      <div style="display: flex; justify-content: flex-end; margin-bottom: 12px;">
        <el-button size="small" @click="loadTasks">
          <el-icon><refresh /></el-icon>
          <span>刷新</span>
        </el-button>
        <el-button size="small" type="primary" style="margin-left: 8px;" @click="openTaskDialog(null)">
          <el-icon><plus /></el-icon>
          <span>新建</span>
        </el-button>
      </div>
      <el-scrollbar max-height="400px">
        <div v-for="t in taskList" :key="t.id" style="padding: 10px 0; border-bottom: 1px solid #f0f0f0;">
          <div style="display: flex; align-items: center; justify-content: space-between;">
            <div style="flex: 1; min-width: 0;">
              <div style="font-size: 13px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
                {{ t.name }}
                <el-tag size="small" :type="t.enabled ? 'success' : 'info'" style="margin-left: 6px;">{{ t.enabled ? '启用' : '停用' }}</el-tag>
              </div>
              <div style="font-size: 11px; color: #909399; margin-top: 2px;">
                {{ t.cronExpr }}
                <span v-if="t.lastRunAt"> · 上次: {{ formatTime(t.lastRunAt) }}</span>
              </div>
            </div>
            <div style="flex-shrink: 0; display: flex; gap: 2px;">
              <el-tooltip content="立即执行">
                <el-button size="small" text type="success" @click="runTask(t.id)">
                  <el-icon><video-play /></el-icon>
                </el-button>
              </el-tooltip>
              <el-tooltip :content="t.enabled ? '停用' : '启用'">
                <el-button size="small" text :type="t.enabled ? 'warning' : 'primary'" @click="toggleTask(t.id)">
                  <el-icon><component :is="t.enabled ? 'video-pause' : 'caret-right'" /></el-icon>
                </el-button>
              </el-tooltip>
              <el-tooltip content="编辑">
                <el-button size="small" text @click="openTaskDialog(t)">
                  <el-icon><edit /></el-icon>
                </el-button>
              </el-tooltip>
              <el-tooltip content="删除">
                <el-button size="small" text type="danger" @click="deleteTask(t.id)">
                  <el-icon><delete /></el-icon>
                </el-button>
              </el-tooltip>
            </div>
          </div>
        </div>
        <el-empty v-if="taskList.length === 0" description="暂无定时任务" :image-size="40"></el-empty>
      </el-scrollbar>
    </el-dialog>

    <!-- 定时任务编辑对话框 -->
    <el-dialog v-model="taskDialogVisible" :title="taskEditing ? '编辑定时任务' : '新建定时任务'" :width="isMobile ? '92%' : '500px'">
      <el-form label-position="top" size="default">
        <el-form-item label="任务名称">
          <el-input v-model="taskForm.name" placeholder="例如：日报生成"></el-input>
        </el-form-item>
        <el-form-item label="Cron 表达式">
          <el-input v-model="taskForm.cronExpr" placeholder="例如：0 0 9 * * ?">
            <template #append>
              <el-dropdown trigger="click" @command="setCronPreset">
                <el-button>预设</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="0 */30 * * * ?">每30分钟</el-dropdown-item>
                    <el-dropdown-item command="0 0 * * * ?">每小时</el-dropdown-item>
                    <el-dropdown-item command="0 0 9 * * ?">每天9点</el-dropdown-item>
                    <el-dropdown-item command="0 0 9 * * MON-FRI">工作日9点</el-dropdown-item>
                    <el-dropdown-item command="0 0 */6 * * ?">每6小时</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="Prompt">
          <el-input v-model="taskForm.prompt" type="textarea" :rows="4" placeholder="要执行的提示词"></el-input>
        </el-form-item>
        <el-form-item label="工作目录">
          <el-input v-model="taskForm.workingDir" placeholder="工作目录路径"></el-input>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="taskDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="taskLoading" @click="saveTask">保存</el-button>
      </template>
    </el-dialog>

    <!-- 历史消息详情弹窗 -->
    <el-drawer v-model="historyDrawerVisible" title="对话详情" :size="isMobile ? '100%' : '50%'" direction="rtl">
      <template #header>
        <div style="display: flex; align-items: center; justify-content: space-between; width: 100%;">
          <span style="font-size: 16px; font-weight: 600;">对话详情</span>
          <div style="display: flex; gap: 8px; margin-right: 24px;">
            <el-button size="small" @click="shareSession">
              <el-icon style="margin-right: 4px;"><share /></el-icon>
              分享
            </el-button>
          </div>
        </div>
      </template>
      <div v-for="(msg, i) in historyMessages" :key="i" class="history-msg">
        <div class="history-msg-role" :style="{ color: msg.role === 'user' ? '#409eff' : '#67c23a' }">
          {{ msg.role === 'user' ? '用户' : '助手' }}
          <span style="font-weight: normal; color: #c0c4cc; margin-left: 8px;">{{ formatTime(msg.timestamp) }}</span>
        </div>
        <!-- /recall 召回卡片 -->
        <div v-if="msg.recall" class="recall-card">
          <div class="recall-card-head" @click="msg.recallOpen = !msg.recallOpen">
            <span class="recall-card-toggle" :class="{expanded: msg.recallOpen}">▶</span>
            <span class="recall-card-title">🔍 召回了 {{ msg.recall.hits.length }} 条历史参考</span>
            <span v-if="msg.recall.query" class="recall-card-query">“{{ msg.recall.query }}”</span>
          </div>
          <div v-show="msg.recallOpen" class="recall-card-body">
            <div v-for="(h, hi) in msg.recall.hits" :key="hi" class="recall-hit">
              <div class="recall-hit-title">{{ hi + 1 }}. {{ h.title }}</div>
              <div v-if="h.conclusion" class="recall-hit-conclusion">{{ h.conclusion }}</div>
            </div>
            <div v-if="!msg.recall.hits.length" class="recall-empty">无匹配历史，已照常发送原消息</div>
          </div>
        </div>
        <!-- user message -->
        <div v-if="msg.role === 'user'" class="history-msg-content">
          <div v-if="msg.bodyText" class="history-msg-text">{{ msg.bodyText }}</div>
          <div v-if="msg.images && msg.images.length" class="message-image-grid">
            <el-image
              v-for="(img, ii) in msg.images"
              :key="ii"
              :src="imageUrl(img)"
              :preview-src-list="msg.images.map(imageUrl)"
              :initial-index="ii"
              fit="cover"
              hide-on-click-modal
              preview-teleported
              class="history-image">
              <template #error>
                <div class="history-image-broken">图片不可用</div>
              </template>
            </el-image>
          </div>
        </div>
        <!-- assistant with parsed segments -->
        <div v-else-if="msg.parsedSegments" class="history-msg-content">
          <template v-for="(seg, si) in msg.parsedSegments" :key="si">
            <div v-if="seg.type === 'text'" class="text-segment-wrap">
              <button class="copy-btn" type="button" title="复制 Markdown" @click="copySegment(seg.content)">📋</button>
              <div class="text-segment md-body" v-html="renderMarkdown(seg.content)"></div>
            </div>
            <div v-else-if="seg.type === 'tool'" class="tool-block">
              <div class="tool-header" @click="seg._expanded = !seg._expanded">
                <span class="tool-toggle" :class="{expanded: seg._expanded}">▶</span>
                <span class="tool-label">{{ seg.name }}</span>
              </div>
              <div v-show="seg._expanded" class="tool-content">{{ seg.content }}</div>
            </div>
            <div v-else-if="seg.type === 'tool_result'" class="tool-block">
              <div class="tool-header" @click="seg._expanded = !seg._expanded">
                <span class="tool-toggle" :class="{expanded: seg._expanded}">▶</span>
                <span class="tool-label">Tool Result</span>
              </div>
              <div v-show="seg._expanded" class="tool-content">{{ seg.content }}</div>
            </div>
            <div v-else-if="seg.type === 'result'" class="text-segment-wrap">
              <button class="copy-btn" type="button" title="复制 Markdown" @click="copySegment(seg.content)">📋</button>
              <div class="text-segment md-body" v-html="renderMarkdown(seg.content)"></div>
            </div>
          </template>
        </div>
        <!-- fallback for assistant without parsed segments -->
        <div v-else class="history-msg-content" v-html="renderMarkdown(msg.content)"></div>
      </div>
      <el-empty v-if="historyMessages.length === 0" description="暂无消息" :image-size="60"></el-empty>
    </el-drawer>
  </el-container>
</template>

<script>
import {
  formatSize,
  renderMarkdown,
  imageUrl,
  formatTime,
  escapeHtml,
  IMAGE_PATH_RE
} from './lib/formatters.js';
import { copySegment } from './lib/clipboard.js';
import { shareSession } from './lib/share-session.js';
import { ref, onMounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { useAuth } from './composables/useAuth.js';
import { useFileSystem } from './composables/useFileSystem.js';
import { useWorktree } from './composables/useWorktree.js';
import { useHistory } from './composables/useHistory.js';
import { useScheduledTask } from './composables/useScheduledTask.js';

export default {
  setup() {
    // auth + file-system 从 composable 引入(FE-R3.2 拆出,原内联状态/方法删除)
    const {
      authEnabled, username, currentUserId, canUseScheduledTask, initAuth, doLogout
    } = useAuth();
    const {
      roots, selectedRoot, workspaceCandidatePath, currentPath, folderList,
      workspaceDialogVisible, previewVisible, previewTitle, previewHtml, previewLoading,
      loadList, handleRootChange, openWorkspaceDialog, confirmWorkspace,
      handleFileCommand, closePreview, isMarkdown, onUploadSuccess, onUploadError,
      initFileSystem, setDefaultRoot
    } = useFileSystem();
    // worktree 从 composable 引入(FE-R3.3 拆出,原内联状态/方法删除)
    const {
      selectedBranch, currentBranch, switchingBranch, savedBranches, switchResult,
      removingBranch, originalWorkspacePath, updatingBranch, updateResult,
      worktreeBranches, branchPopoverVisible, branchOptions,
      saveWorktreeState, clearWorktreeState, loadWorktreeBranches,
      switchBranch, updateBranch, clearBranch, removeSavedBranch,
      restoreWorktreeState
    } = useWorktree({ selectedRoot, currentPath, loadList });
    // 对话默认模型由管理后台控制: GET /api/chat/agent-default 返回 {agentType, version}。
    // 「强制全员跟随」: 本地记录的版本(agent_type_force_version)与服务端不一致时, 覆盖本地选择
    // (agent_type)并切到服务端默认、写回新版本; 一致则尊重用户后续手动选择。
    // 同步初始化先用本地缓存(或 CLAUDE 兜底), 服务端版本回来后由 applyServerAgentDefault 再按需强制。
    const readPreferredAgentType = () => {
      const stored = localStorage.getItem('agent_type');
      return stored || 'CLAUDE';
    };
    const agentType = ref(readPreferredAgentType());
    // 当前 ChatPanel 的会话标识:由组件 session-created 回填 / 宿主点历史时设置,
    // 驱动顶栏 Agent 选择器锁定,并作为 initialSessionId/initialResumeId 传给组件触发 resume。
    const activeSessionId = ref('');
    const activeResumeId = ref('');
    const starting = ref(false);
    // 历史 + 定时任务 从 composable 引入(FE-R3.4 拆出,原内联状态/方法删除)
    const {
      historyList, historyPage, historyHasMore, historyLoading,
      historyMessages, historyDrawerVisible, currentHistorySessionId,
      groupedHistory, loadHistory, onHistoryScroll, canDelete,
      deleteHistory, viewHistory, resumeHistory, shareSessionFor
    } = useHistory({ currentUserId, agentType, activeResumeId, activeSessionId });
    const {
      taskList, taskDialogVisible, taskEditing, taskForm, taskLoading, taskManagerVisible,
      loadTasks, openTaskDialog, saveTask, deleteTask, toggleTask, runTask, setCronPreset
    } = useScheduledTask({ currentPath });

    // --- chat-rag 召回开关探测 (召回历史浏览已迁至管理后台 /admin/refinery.html) ---
    const chatRagEnabled = ref(false);

    const sidebarVisible = ref(false);
    const isMobile = ref(window.innerWidth <= 768);

    window.addEventListener('resize', () => {
      isMobile.value = window.innerWidth <= 768;
      if (!isMobile.value) sidebarVisible.value = false;
    });

    // ========== 初始化 ==========
    const init = async () => {
      // auth: useAuth.initAuth(未登录跳转,返回 false 时 init 终止)
      const authed = await initAuth();
      if (!authed) return;
      // 探测 chat-rag 是否启用: enabled=false 时 controller 不装配, /chunks 返回 404 -> 隐藏入口
      try {
        const probe = await fetch('/api/refinery/chunks?page=1&size=1');
        chatRagEnabled.value = probe.ok;
      } catch (e) {
        // 忽略，保留 false
      }
      // 对话默认模型「强制全员跟随」: 服务端版本与本地不一致即覆盖本地选择并切换(仅在无进行中会话时切)。
      try {
        const def = await fetch('/api/chat/agent-default').then(r => r.json());
        if (def && def.agentType) {
          const appliedVer = localStorage.getItem('agent_type_force_version');
          if (appliedVer !== String(def.version)) {
            localStorage.setItem('agent_type', def.agentType);
            localStorage.setItem('agent_type_force_version', String(def.version));
            if (!activeSessionId.value) {
              agentType.value = def.agentType;
            }
          }
        }
      } catch (e) {
        // 忽略: 取不到默认值就保留本地选择
      }
      // fs roots(useFileSystem.initFileSystem) + worktree 恢复(useWorktree.restoreWorktreeState)
      const data = await initFileSystem();
      if (data.length > 0) {
        const restored = await restoreWorktreeState(data);
        if (!restored) await setDefaultRoot();
      }
    };

    // ========== 会话管理(宿主侧) ==========
    // 聊天闭环已迁入 ChatPanel 组件;宿主只管「开新对话」:置空 active*,
    // 组件经 initialSessionId='' 自行清空,顶栏 agent 恢复用户偏好。
    const newConversation = async () => {
      activeSessionId.value = '';
      activeResumeId.value = '';
      agentType.value = readPreferredAgentType();
      ElMessage.success('新对话已就绪');
    };

    // formatTime / escapeHtml 由 lib/formatters.js 提供,顶部已解构。

    // ========== Agent 类型 ==========
    const onAgentTypeChange = (val) => {
      // 会话开始后下拉是 disabled 的, 这里只处理新建态的切换
      localStorage.setItem('agent_type', val);
      ElMessage.info({ message: '已切换到 ' + val, duration: 2000 });
    };

    // ========== ChatPanel 宿主回调 ==========
    // 组件新建会话:回填 active* 锁定顶栏 Agent,并刷新历史列表让新会话显现
    const onSessionCreated = (payload) => {
      activeSessionId.value = payload.sessionId;
      activeResumeId.value = '';
      loadHistory(true);
    };
    // 组件流结束 / 回退后:重拉历史列表,同步标题与消息数
    const onRefreshHistory = () => {
      loadHistory(true);
    };

    // ========== 生命周期 ==========
    onMounted(async () => {
      await init();
      await loadHistory(true);
      await loadTasks();
    });

    // 切工作目录:置空 active*,ChatPanel 经 workingDir / initialSession 自行清空并重载命令
    watch(currentPath, () => {
      activeSessionId.value = '';
      activeResumeId.value = '';
    });

    watch(branchPopoverVisible, (v) => {
      if (v) loadWorktreeBranches();
    });

    return {
      roots,
      selectedRoot,
      workspaceCandidatePath,
      currentPath,
      folderList,
      previewVisible,
      previewTitle,
      previewHtml,
      previewLoading,
      isMarkdown,
      closePreview,
      agentType,
      activeSessionId,
      activeResumeId,
      username,
      starting,
      handleRootChange,
      loadList,
      openWorkspaceDialog,
      confirmWorkspace,
      newConversation,
      onAgentTypeChange,
      onSessionCreated,
      onRefreshHistory,
      formatSize,
      handleFileCommand,
      selectedBranch,
      currentBranch,
      switchingBranch,
      savedBranches,
      branchOptions,
      branchPopoverVisible,
      switchResult,
      removingBranch,
      switchBranch,
      updateBranch,
      updatingBranch,
      updateResult,
      clearBranch,
      removeSavedBranch,
      onUploadSuccess,
      onUploadError,
      renderMarkdown,
      imageUrl,
      copySegment,
      historyList,
      historyLoading,
      historyHasMore,
      historyMessages,
      historyDrawerVisible,
      loadHistory,
      onHistoryScroll,
      deleteHistory,
      canDelete,
      canUseScheduledTask,
      viewHistory,
      resumeHistory,
      shareSession,
      formatTime,
      escapeHtml,
      taskList,
      taskDialogVisible,
      taskEditing,
      taskForm,
      taskLoading,
      loadTasks,
      openTaskDialog,
      saveTask,
      deleteTask,
      toggleTask,
      runTask,
      setCronPreset,
      workspaceDialogVisible,
      taskManagerVisible,
      chatRagEnabled,
      sidebarVisible,
      isMobile,
      groupedHistory,
      authEnabled,
      doLogout,
    };
  }
};
</script>
