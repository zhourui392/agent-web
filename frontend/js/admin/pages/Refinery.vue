<template>
  <admin-shell active="refinery" @ready="onReady">
    <template #header-actions>
      <el-button text :loading="ragLoading" @click="loadHistory">刷新</el-button>
    </template>

    <div class="view-wrap">
      <div class="conv-toolbar">
        <el-radio-group v-model="ragStatus" @change="onStatusChange">
          <el-radio-button label="all">入库(含过期)</el-radio-button>
          <el-radio-button label="active">可召回</el-radio-button>
          <el-radio-button label="discarded">已丢弃(低分)</el-radio-button>
        </el-radio-group>
      </div>

      <el-table v-loading="ragLoading" :data="ragList" empty-text="暂无召回记录" border size="small">
        <el-table-column label="标题 / 结论" min-width="280">
          <template #default="{ row }">
            <div class="title" style="font-size:13px; font-weight:500;">{{ row.title }}</div>
            <div
v-if="row.conclusion" class="muted" style="font-size:12px; margin-top:2px;
                 overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">{{ row.conclusion }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)" disable-transitions>
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="TTL" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.ttlCategory" size="small" type="info" disable-transitions>{{ row.ttlCategory }}</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="评分" width="120">
          <template #default="{ row }">
            <span>{{ formatScore(row.score) }}</span>
            <span
v-if="row.status === 'DISCARDED' && typeof row.threshold === 'number'"
                  style="color:#e6a23c; font-size:12px; margin-left:4px;">&lt; {{ formatScore(row.threshold) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Agent" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.agentType === 'CODEX' ? 'warning' : 'primary'" disable-transitions>
              {{ row.agentType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="viewSource(row.sourceSessionId)">查看来源</el-button>
            <el-button text type="danger" size="small" @click="deleteChunk(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="conv-pager">
        <el-pagination
background layout="total, sizes, prev, pager, next, jumper"
                       :total="ragTotal" :page-size="ragSize" :current-page="ragPage"
                       :page-sizes="[10, 20, 50, 100]"
                       @current-change="onPageChange" @size-change="onSizeChange"></el-pagination>
      </div>
    </div>

    <!-- 来源会话详情抽屉 -->
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
  </admin-shell>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';
import { renderMarkdown, imageUrl, formatTime } from '../../lib/formatters.js';
import { enrichMessage, ROLE_LABELS, roleLabel } from '../../lib/message-view.js';
import { copySegment } from '../../lib/clipboard.js';

const ragList = ref<any[]>([]);
const ragTotal = ref<number>(0);
const ragPage = ref<number>(1);
const ragSize = ref<number>(20);
const ragStatus = ref<string>('all');
const ragLoading = ref<boolean>(false);

// ---- 来源会话详情抽屉(复用对话记录页渲染) ----
const detailOpen = ref<boolean>(false);
const detailLoading = ref<boolean>(false);
const detail = ref<any>(null);
const detailTitle = ref<string>('来源会话');

const formatScore = (s: number | undefined | null): string => (typeof s === 'number' ? s.toFixed(2) : '-');

async function loadHistory(): Promise<void> {
  if (ragLoading.value) {
    return;
  }
  ragLoading.value = true;
  try {
    // "已丢弃(低分)"走独立端点(不带 status); 其余走 chunks 库存端点
    const url = ragStatus.value === 'discarded'
      ? '/api/refinery/discarded?page=' + ragPage.value + '&size=' + ragSize.value
      : '/api/refinery/chunks?page=' + ragPage.value
          + '&size=' + ragSize.value + '&status=' + ragStatus.value;
    const res = await fetch(url);
    if (!res.ok) {
      throw new Error(await res.text());
    }
    const data = await res.json();
    ragList.value = data.items || [];
    ragTotal.value = data.total || 0;
  } catch (e) {
    ElMessage.error('加载召回历史失败: ' + (e.message || '未知错误'));
  } finally {
    ragLoading.value = false;
  }
}

function onPageChange(newPage: number): void {
  ragPage.value = newPage;
  loadHistory();
}

function onSizeChange(newSize: number): void {
  ragSize.value = newSize;
  ragPage.value = 1;
  loadHistory();
}

function onStatusChange(): void {
  ragPage.value = 1;
  loadHistory();
}

/** 逐条硬删召回记录: 确认 → DELETE → 重拉当前页(空页则回退一页)。 */
async function deleteChunk(chunk: any): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '确定删除该召回记录？删除后不可恢复。', '确认删除',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' });
  } catch (e) {
    return; // 用户取消
  }
  try {
    // 丢弃记录与 chunk 走不同删除端点
    const base = ragStatus.value === 'discarded' ? '/api/refinery/discarded/' : '/api/refinery/chunks/';
    const res = await fetch(base + encodeURIComponent(chunk.id), { method: 'DELETE' });
    if (!res.ok) {
      throw new Error(await res.text());
    }
    ElMessage.success('已删除');
    // 删的是当前页最后一条且非首页时回退一页, 避免停在空页
    if (ragList.value.length === 1 && ragPage.value > 1) {
      ragPage.value -= 1;
    }
    loadHistory();
  } catch (e) {
    ElMessage.error('删除失败: ' + (e.message || '未知错误'));
  }
}

const STATUS_LABELS: Record<string, string> = { ACTIVE: '可召回', DISCARDED: '已丢弃', ARCHIVED: '已过期' };
const STATUS_TYPES: Record<string, string> = { ACTIVE: 'success', DISCARDED: 'warning', ARCHIVED: 'info' };
const statusLabel = (s: string): string => STATUS_LABELS[s] || '已过期';
const statusTagType = (s: string): string => STATUS_TYPES[s] || 'info';

/** 查看来源会话:走 admin 跨用户对话详情端点;诊断来源/已删会话会 404,友好提示。 */
async function viewSource(sessionId: string): Promise<void> {
  if (!sessionId) {
    ElMessage.info('该记录无来源会话');
    return;
  }
  detailOpen.value = true;
  detailLoading.value = true;
  detail.value = null;
  detailTitle.value = '来源会话';
  try {
    const resp = await fetch('/api/metrics/conversations/' + encodeURIComponent(sessionId));
    if (!resp.ok) {
      ElMessage.warning('来源会话不存在或非对话来源(如诊断任务)');
      detailOpen.value = false;
      return;
    }
    const data = await resp.json();
    data.messages = Array.isArray(data.messages) ? data.messages.map(enrichMessage) : [];
    detail.value = data;
    detailTitle.value = data.record.title || '来源会话';
  } catch (e) {
    ElMessage.error('加载来源会话失败: ' + e);
    detailOpen.value = false;
  } finally {
    detailLoading.value = false;
  }
}

function fmtTime(iso: string | null | undefined): string {
  if (!iso) {
    return '—';
  }
  return String(iso).replace('T', ' ').replace(/\..*$/, '').replace('Z', '').slice(0, 19);
}

const onReady = loadHistory;
</script>