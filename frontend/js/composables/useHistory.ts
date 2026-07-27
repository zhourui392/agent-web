/**
 * useHistory composable: app.js 主页 历史记录切片(FE-R3.4)。
 *
 * 从 app.js setup 抽出: 历史状态(historyList/historyPage/historyHasMore/historyLoading/
 * historyMessages/historyDrawerVisible/currentHistorySessionId) + groupedHistory computed +
 * loadHistory/onHistoryScroll/canDelete/deleteHistory/viewHistory/resumeHistory/shareSessionFor。
 *
 * 与外部耦合: canDelete 读 currentUserId(useAuth), resumeHistory 写 agentType/activeResumeId/
 * activeSessionId(app.js 编排态),故这四项以参数注入。shareSession/mapMessages 走 lib。
 *
 * 行为照搬 app.js 原内联实现,零逻辑变更。依赖 ElMessage/ElMessageBox + lib/message-view +
 * lib/share-session。
 */
import { ref, computed, reactive, type Ref, type ComputedRef } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';
import { mapMessages } from '../lib/message-view.js';
import { shareSession } from '../lib/share-session.js';

interface HistoryItem {
  sessionId: string;
  createdAt: string;
  userId?: string;
  agentType?: string;
  resumeId?: string;
  running?: boolean;
  [key: string]: unknown;
}

interface HostState {
  currentUserId: Ref<string>;
  agentType: Ref<string>;
  activeResumeId: Ref<string>;
  activeSessionId: Ref<string>;
}

export function useHistory(host: HostState): {
  historyList: Ref<HistoryItem[]>;
  historyPage: Ref<number>;
  historyHasMore: Ref<boolean>;
  historyLoading: Ref<boolean>;
  historyMessages: Ref<unknown[]>;
  historyDrawerVisible: Ref<boolean>;
  currentHistorySessionId: Ref<string>;
  groupedHistory: ComputedRef<{ label: string; items: HistoryItem[] }[]>;
  loadHistory: (reset?: boolean) => Promise<void>;
  onHistoryScroll: (e: { target?: { scrollHeight: number; scrollTop: number; clientHeight: number } } | { scrollHeight: number; scrollTop: number; clientHeight: number }) => void;
  canDelete: (h: HistoryItem) => boolean;
  deleteHistory: (sid: string) => Promise<void>;
  viewHistory: (sid: string) => Promise<void>;
  resumeHistory: (session: HistoryItem | null | undefined) => void;
  shareSessionFor: (sid?: string) => Promise<void>;
} {
  const historyList = ref<HistoryItem[]>([]);
  const historyPage = ref(1);
  const historyPageSize = 20;
  const historyHasMore = ref(true);
  const historyLoading = ref(false);
  const historyMessages = ref<unknown[]>([]);
  const historyDrawerVisible = ref(false);
  const currentHistorySessionId = ref('');

  const groupedHistory = computed(() => {
    const now = new Date();
    const today = now.toDateString();
    const yesterday = new Date(now.getTime() - 86400000).toDateString();
    const groups: { today: HistoryItem[]; yesterday: HistoryItem[]; older: HistoryItem[] } = { today: [], yesterday: [], older: [] };

    historyList.value.forEach((h) => {
      const d = new Date(h.createdAt).toDateString();
      if (d === today) groups.today.push(h);
      else if (d === yesterday) groups.yesterday.push(h);
      else groups.older.push(h);
    });

    const result: { label: string; items: HistoryItem[] }[] = [];
    if (groups.today.length) result.push({ label: '今天', items: groups.today });
    if (groups.yesterday.length) result.push({ label: '昨天', items: groups.yesterday });
    if (groups.older.length) result.push({ label: '更早', items: groups.older });
    return result;
  });

  const loadHistory = async (reset?: boolean) => {
    if (reset) {
      historyPage.value = 1;
      historyHasMore.value = true;
      historyList.value = [];
    }
    if (!historyHasMore.value || historyLoading.value) return;
    historyLoading.value = true;
    try {
      const data: HistoryItem[] = await fetch('/api/chat/sessions?page=' + historyPage.value + '&size=' + historyPageSize).then((r) => r.json());
      const activeBySession: Record<string, boolean> = {};
      try {
        const activeResponse = await fetch('/api/chat/runs/active');
        if (activeResponse.ok) {
          const activeRuns: { sessionId: string }[] = await activeResponse.json();
          activeRuns.forEach((run) => { activeBySession[run.sessionId] = true; });
        }
      } catch (e) { /* feature flag 关闭或探测失败时不影响历史列表 */ }
      const decorated = data.map((item) => Object.assign({}, item, {
        running: !!activeBySession[item.sessionId],
      }));
      historyList.value = historyList.value.concat(decorated);
      historyHasMore.value = data.length >= historyPageSize;
      historyPage.value++;
    } catch (e) {
      ElMessage.error('加载历史记录失败');
    } finally {
      historyLoading.value = false;
    }
  };

  const onHistoryScroll = (e: { target?: { scrollHeight: number; scrollTop: number; clientHeight: number } } | { scrollHeight: number; scrollTop: number; clientHeight: number }) => {
    const wrap = (e as { target?: { scrollHeight: number; scrollTop: number; clientHeight: number } }).target || (e as { scrollHeight: number; scrollTop: number; clientHeight: number });
    if (wrap.scrollHeight - wrap.scrollTop - wrap.clientHeight < 50) {
      loadHistory(false);
    }
  };

  // 仅创建者(或无归属老数据)可删除; 删他人会话的按钮隐藏, 后端再兜底 403
  const canDelete = (h: HistoryItem) => !h.userId || h.userId === host.currentUserId.value;

  const deleteHistory = async (sid: string) => {
    try {
      await ElMessageBox.confirm('确定删除该对话记录？', '确认删除', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      });
    } catch (e) {
      return; // 用户取消
    }
    try {
      const r = await fetch('/api/chat/session/' + encodeURIComponent(sid), { method: 'DELETE' });
      if (!r.ok) {
        ElMessage.error(r.status === 403 ? '只能删除自己创建的对话' : '删除失败');
        return;
      }
      historyList.value = historyList.value.filter((h) => h.sessionId !== sid);
      ElMessage.success('已删除');
    } catch (e) {
      ElMessage.error('删除失败');
    }
  };

  const viewHistory = async (sid: string) => {
    try {
      currentHistorySessionId.value = sid;
      const data = await fetch('/api/chat/session/' + encodeURIComponent(sid) + '/messages').then((r) => r.json());
      historyMessages.value = mapMessages(data, { withRecall: true });
      historyDrawerVisible.value = true;
    } catch (e) {
      ElMessage.error('加载消息失败');
    }
  };

  // 恢复历史会话:设宿主 Agent 锁定态与 active*,由 ChatPanel 经 initialSessionId 拉消息续聊。
  // 先设 resumeId 再设 sessionId,确保组件 watch(initialSessionId) 触发时拿到正确的 initialResumeId。
  const resumeHistory = (session: HistoryItem | null | undefined) => {
    if (!session || !session.sessionId) return;
    if (session.agentType) host.agentType.value = session.agentType;
    host.activeResumeId.value = session.resumeId || '';
    host.activeSessionId.value = session.sessionId;
  };

  const shareSessionFor = (sid?: string) => shareSession(
    (typeof sid === 'string' && sid) ? sid : currentHistorySessionId.value
  );

  return {
    historyList, historyPage, historyHasMore, historyLoading,
    historyMessages, historyDrawerVisible, currentHistorySessionId,
    groupedHistory, loadHistory, onHistoryScroll, canDelete,
    deleteHistory, viewHistory, resumeHistory, shareSessionFor,
  };
}