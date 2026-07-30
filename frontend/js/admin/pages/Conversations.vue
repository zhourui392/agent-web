<template>
  <admin-shell active="conversations" @ready="onReady">
    <template #header-actions>
      <el-button text :loading="convLoading" @click="loadConversations">刷新</el-button>
    </template>

    <div class="view-wrap">
      <div class="conv-toolbar">
        <el-input
v-model="convKeyword" placeholder="搜索标题 / 用户名 / 工号" clearable
                  @keyup.enter="searchConversations" @clear="searchConversations"></el-input>
        <el-button type="primary" :loading="convLoading" @click="searchConversations">搜索</el-button>
      </div>

      <el-table v-loading="convLoading" :data="conversations" empty-text="暂无对话记录" border size="small">
        <el-table-column label="用户" min-width="120">
          <template #default="{ row }">
            <div class="user-cell">
              <div class="name">{{ row.userName || '—' }}</div>
              <div class="uid">{{ row.userId || '匿名/系统' }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="Agent" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.agentType === 'CODEX' ? 'warning' : 'primary'" disable-transitions>
              {{ row.agentType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ row.title || '新对话' }}</template>
        </el-table-column>
        <el-table-column label="消息数" width="80" align="right" prop="messageCount"></el-table-column>
        <el-table-column label="反馈" width="90">
          <template #default="{ row }">
            <el-tag
v-if="row.feedbackRating" size="small" disable-transitions
                    :type="feedbackTagType(row.feedbackRating)">{{ feedbackLabel(row.feedbackRating) }}</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="openDetail(row.sessionId)">查看</el-button>
            <el-button text type="primary" size="small" data-test="conversation-tool-invocations"
                       @click="openToolInvocations(row)">工具调用</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="conv-pager">
        <el-pagination
background layout="total, prev, pager, next"
                       :total="convTotal" :page-size="convSize" :current-page="convPage"
                       @current-change="onPageChange"></el-pagination>
      </div>
    </div>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailOpen" :title="detailTitle" size="48%" direction="rtl">
      <div v-loading="detailLoading">
        <div v-if="detail" class="drawer-meta">
          <div>会话 ID:{{ detail.record.sessionId }}</div>
          <div>用户:{{ detail.record.userName || '—' }}({{ detail.record.userId || '匿名/系统' }})· IP {{ detail.record.clientIp || '—' }}</div>
          <div>Agent:{{ detail.record.agentType }} · 创建:{{ fmtTime(detail.record.createdAt) }} · 消息 {{ detail.record.messageCount }} 条</div>
        </div>
        <div v-if="detail && detail.messages.length">
          <div v-for="(msg, i) in detail.messages" :key="i" class="history-msg">
            <div class="history-msg-role" :style="{ color: msg.role === 'user' ? '#409eff' : '#67c23a' }">
              {{ roleLabel(msg.role) }}
              <span style="font-weight: normal; color: #c0c4cc; margin-left: 8px;">{{ formatTime(msg.timestamp) }}</span>
            </div>
            <!-- 用户消息:正文 + 图片 -->
            <div v-if="msg.role === 'user'" class="history-msg-content">
              <div v-if="msg.bodyText" class="history-msg-text">{{ msg.bodyText }}</div>
              <div v-if="msg.images && msg.images.length" class="message-image-grid">
                <el-image
v-for="(img, ii) in msg.images" :key="ii" :src="imageUrl(img)"
                          :preview-src-list="msg.images.map(imageUrl)" :initial-index="ii"
                          fit="cover" hide-on-click-modal preview-teleported class="history-image">
                  <template #error><div class="history-image-broken">图片不可用</div></template>
                </el-image>
              </div>
            </div>
            <!-- 助手消息:解析后的文本/工具分段 -->
            <div v-else-if="msg.parsedSegments" class="history-msg-content">
              <template v-for="(seg, si) in msg.parsedSegments" :key="si">
                <div v-if="seg.type === 'text' || seg.type === 'result'" class="text-segment-wrap">
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
              </template>
            </div>
            <!-- 兜底:直接 markdown 渲染 -->
            <div v-else class="history-msg-content" v-html="renderMarkdown(msg.content)"></div>
          </div>
        </div>
        <el-empty v-else-if="detail" description="暂无消息" :image-size="60"></el-empty>
      </div>
    </el-drawer>

    <el-drawer v-model="toolOpen" title="工具调用" size="62%" direction="rtl">
      <div v-loading="toolLoading">
        <el-table :data="toolRows" border size="small" empty-text="暂无工具调用"
                  data-test="conversation-tool-invocation-table">
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column label="Provider" width="90" prop="provider"></el-table-column>
          <el-table-column label="类别" width="100">
            <template #default="{ row }">{{ kindLabel(row.invocationKind) }}</template>
          </el-table-column>
          <el-table-column label="工具 / Skill" min-width="140">
            <template #default="{ row }"><b>{{ row.displayToolName || '命令执行' }}</b></template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="输入摘要" min-width="220" show-overflow-tooltip prop="inputSummary"></el-table-column>
          <el-table-column label="操作" width="70" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openToolDetail(row.id)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="conv-pager">
          <el-pagination background layout="total, prev, pager, next" :total="toolTotal"
                         :page-size="toolSize" :current-page="toolPage"
                         @current-change="changeToolPage"></el-pagination>
        </div>
      </div>
    </el-drawer>

    <tool-invocation-detail-drawer
      v-model:open="toolDetailOpen"
      :loading="toolDetailLoading"
      :detail="toolDetail"
      :display-name="toolDetailDisplayName"
    />
  </admin-shell>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { renderMarkdown, imageUrl, formatTime, formatBeijingDateTime } from '../../lib/formatters.js';
import { enrichMessage, ROLE_LABELS, roleLabel } from '../../lib/message-view.js';
import { copySegment } from '../../lib/clipboard.js';
import { fetchJson, withLoading } from '../../lib/admin-fetch.ts';
import ToolInvocationDetailDrawer from '../components/ToolInvocationDetailDrawer.vue';

const conversations = ref<any[]>([]);
const convTotal = ref<number>(0);
const convPage = ref<number>(1);
const convSize = ref<number>(20);
const convKeyword = ref<string>('');
const convLoading = ref<boolean>(false);

// ---- 详情抽屉 ----
const detailOpen = ref<boolean>(false);
const detailLoading = ref<boolean>(false);
const detail = ref<any>(null);
const detailTitle = ref<string>('对话详情');

const toolOpen = ref<boolean>(false);
const toolLoading = ref<boolean>(false);
const toolRows = ref<any[]>([]);
const toolTotal = ref<number>(0);
const toolPage = ref<number>(1);
const toolSize = ref<number>(20);
const toolSessionId = ref<string>('');
const toolDetailOpen = ref<boolean>(false);
const toolDetailLoading = ref<boolean>(false);
const toolDetail = ref<any>(null);
const toolDetailDisplayName = computed<string>(() => {
  if (!toolDetail.value) return '—';
  const row = toolRows.value.find((item) => item.id === toolDetail.value.id);
  return row?.displayToolName || toolDetail.value.skillName || toolDetail.value.toolName || '命令执行';
});

async function loadConversations(): Promise<void> {
  convLoading.value = true;
  try {
    const params = new URLSearchParams({ page: String(convPage.value), size: String(convSize.value) });
    if (convKeyword.value.trim()) {
      params.set('keyword', convKeyword.value.trim());
    }
    const data = await fetch('/api/metrics/conversations?' + params.toString()).then((r) => r.json());
    conversations.value = Array.isArray(data.rows) ? data.rows : [];
    convTotal.value = data.total || 0;
  } catch (e) {
    ElMessage.error('加载对话记录失败: ' + e);
  } finally {
    convLoading.value = false;
  }
}

function searchConversations(): void {
  convPage.value = 1;
  loadConversations();
}

function onPageChange(page: number): void {
  convPage.value = page;
  loadConversations();
}

async function openDetail(sessionId: string): Promise<void> {
  detailOpen.value = true;
  detailLoading.value = true;
  detail.value = null;
  detailTitle.value = '对话详情';
  try {
    const resp = await fetch('/api/metrics/conversations/' + encodeURIComponent(sessionId));
    if (!resp.ok) {
      ElMessage.error('会话不存在或已删除');
      detailOpen.value = false;
      return;
    }
    const data = await resp.json();
    data.messages = Array.isArray(data.messages) ? data.messages.map(enrichMessage) : [];
    detail.value = data;
    detailTitle.value = data.record.title || '对话详情';
  } catch (e) {
    ElMessage.error('加载详情失败: ' + e);
    detailOpen.value = false;
  } finally {
    detailLoading.value = false;
  }
}

async function loadToolInvocations(): Promise<void> {
  const params = new URLSearchParams({
    page: String(toolPage.value), size: String(toolSize.value), sessionId: toolSessionId.value,
  });
  try {
    await withLoading(toolLoading, async () => {
      const data = await fetchJson('/api/admin-tool-invocations?' + params.toString());
      toolRows.value = Array.isArray(data.items) ? data.items : [];
      toolTotal.value = data.total || 0;
    });
  } catch (e) {
    ElMessage.error('加载工具调用失败: ' + e);
  }
}

function openToolInvocations(row: any): void {
  toolSessionId.value = row.sessionId;
  toolPage.value = 1;
  toolRows.value = [];
  toolOpen.value = true;
  loadToolInvocations();
}

function changeToolPage(page: number): void {
  toolPage.value = page;
  loadToolInvocations();
}

async function openToolDetail(id: number): Promise<void> {
  toolDetailOpen.value = true;
  toolDetail.value = null;
  try {
    await withLoading(toolDetailLoading, async () => {
      toolDetail.value = await fetchJson('/api/admin-tool-invocations/' + id);
    });
  } catch (e) {
    ElMessage.error('加载工具调用详情失败: ' + e);
    toolDetailOpen.value = false;
  }
}

function kindLabel(value: string): string {
  return ({ TOOL_USE: '工具', COMMAND_EXECUTION: '命令执行', SKILL: 'Skill' } as Record<string, string>)[value] || value;
}

function statusLabel(value: string): string {
  return ({ STARTED: '执行中', SUCCEEDED: '成功', FAILED: '失败', INCOMPLETE: '不完整', UNKNOWN: '未知' } as Record<string, string>)[value] || value;
}

function statusType(value: string): string {
  if (value === 'SUCCEEDED') return 'success';
  if (value === 'FAILED') return 'danger';
  if (value === 'INCOMPLETE') return 'warning';
  return 'info';
}

function fmtTime(iso: string | number | null | undefined): string {
  if (!iso) {
    return '—';
  }
  if (typeof iso === 'number') {
    return new Date(iso).toLocaleString('zh-CN');
  }
  return formatBeijingDateTime(iso) || '—';
}

const FEEDBACK_LABELS: Record<string, string> = { CORRECT: '正确', PARTIALLY_CORRECT: '部分正确', INCORRECT: '错误' };
const FEEDBACK_TYPES: Record<string, string> = { CORRECT: 'success', PARTIALLY_CORRECT: 'warning', INCORRECT: 'danger' };

const feedbackLabel = (r: string): string => FEEDBACK_LABELS[r] || r;
const feedbackTagType = (r: string): string => FEEDBACK_TYPES[r] || 'info';

async function onReady(): Promise<void> {
  await loadConversations();
  const sessionId = new URLSearchParams(location.search).get('sessionId');
  if (sessionId) {
    await openDetail(sessionId);
  }
}
</script>