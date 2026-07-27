/**
 * useHarnessSse: Harness run SSE 订阅 composable。
 *
 * 从 useHarness 拆出的独立关注点：监听 selectedRun 变化时重连 SSE，
 * 收到事件后刷新 run 详情 + conversation，组件卸载时关闭连接。
 *
 * 依赖: vue(watch/onBeforeUnmount)、lib/resumable-sse-client(open)。
 * 调用方需在 setup 阶段调用，以确保 onBeforeUnmount 正确注册。
 */
import { open as openResumableSse } from '../../lib/resumable-sse-client.js';
import { watch, onBeforeUnmount, type Ref } from 'vue';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type AnyRef = ReturnType<typeof Ref<any>>;

interface HarnessSseDeps {
  selectedRun: Ref<any>;
  conversationMessages: Ref<any[]>;
  runUrl: (runId: string) => string;
  api: (path: string, options?: RequestInit) => Promise<any>;
  loadStageResources: () => Promise<void>;
  scrollConversationToEnd: () => void;
  showError: (msg: string, error: any) => void;
}

export function useHarnessSse(deps: HarnessSseDeps) {
  let sseClient: ReturnType<typeof openResumableSse> | null = null;
  let sseRefreshInFlight = false;

  function closeSse() {
    if (sseClient) { sseClient.close(); sseClient = null; }
  }

  function connectSse(runId: string) {
    closeSse();
    if (!runId) return;
    const url = `/api/harness/runs/${encodeURIComponent(runId)}/stream`;
    sseClient = openResumableSse(url, { after: 0 });
    // 通配监听：收到任何 SSE 事件就刷新 run 详情 + conversation。
    // harness 事件量低（一次操作 1-3 个事件），不需要按 event type 精细刷新。
    sseClient.addEventListener('*', () => { refreshFromSse(runId); });
  }

  async function refreshFromSse(runId: string) {
    if (sseRefreshInFlight || !deps.selectedRun.value || deps.selectedRun.value.runId !== runId) {
      return;
    }
    sseRefreshInFlight = true;
    try {
      const base = deps.runUrl(runId);
      const values = await Promise.all([deps.api(base), deps.api(base + '/conversation')]);
      if (!deps.selectedRun.value || deps.selectedRun.value.runId !== runId) {
        return;
      }
      const nextRun = values[0];
      const prevRun = deps.selectedRun.value;
      // 仅在 run 实质变化（updatedAt/status）时才替换 selectedRun 与消息列表，
      // 避免每次 SSE 事件全量替换触发 el-descriptions/el-table/v-html 重渲染造成抖动。
      if (!prevRun || prevRun.updatedAt !== nextRun.updatedAt
          || prevRun.status !== nextRun.status) {
        deps.selectedRun.value = nextRun;
        deps.conversationMessages.value = Array.isArray(values[1]) ? values[1] : [];
      }
      await deps.loadStageResources();
      deps.scrollConversationToEnd();
    } catch (error: any) {
      deps.showError('刷新 Harness 状态失败', error);
    } finally {
      sseRefreshInFlight = false;
    }
  }

  // selectedRun 变化时重连 SSE（切换 run 或首次加载）。
  watch(() => deps.selectedRun.value?.runId, (newRunId) => {
    if (newRunId) {
      connectSse(newRunId);
    } else {
      closeSse();
    }
  });

  onBeforeUnmount(() => {
    closeSse();
  });
}