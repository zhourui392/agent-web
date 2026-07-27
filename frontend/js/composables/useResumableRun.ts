/**
 * useResumableRun composable: ChatPanel 可恢复 ChatRun 编排切片(FE-R3.6)。
 *
 * 从 chat-panel.js setup 抽出: run 状态(activeRunId/runStatus/lastAppliedEventSeq/
 * reconnecting + 闭包变量 currentES/restoringActiveRun/runStore) + SSE 订阅/事件处理/
 * run 提交/恢复/停止/重置。
 *
 * 这是 ChatPanel 最复杂的切片,与组件状态紧密耦合:messages/userInput/sending/
 * sessionId/resumeId/chatContainer/ragRecall/pendingImages/pendingFile/workingDir +
 * ensureSession/addMessage/userMessageEntry/reloadMessages/loadFeedback/emit 全部
 * 以参数注入。行为照搬 chat-panel.js 原内联实现,零逻辑变更。
 *
 * 依赖 lib/chat-run-state(createStore/selectActiveRun) + lib/resumable-sse-client(open) +
 * lib/formatters(parseStreamJson/isStreamJson) + ElMessage/nextTick。
 */
import { ref, nextTick, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { createStore, selectActiveRun } from '../lib/chat-run-state.js';
import { open as openResumableSse } from '../lib/resumable-sse-client.js';
import { parseStreamJson, isStreamJson } from '../lib/formatters.js';

// 消息结构宽松 typing:chat-panel.js 的 messages 是 ref([]),元素含 role/id/segments 等
interface ChatMessage {
  id: number | null;
  role: string;
  text?: string;
  bodyText?: string;
  images?: string[];
  segments?: { type: string; content: string }[];
  recall?: unknown;
  recallOpen?: boolean;
  [key: string]: unknown;
}

interface PendingImage { path: string; previewUrl: string; name: string }
interface PendingFile { path: string; name: string; size: number }

interface ResumableRunParams {
  messages: Ref<ChatMessage[]>;
  userInput: Ref<string>;
  sending: Ref<boolean>;
  sessionId: Ref<string>;
  resumeId: Ref<string>;
  chatContainer: Ref<HTMLElement | null>;
  ragRecall: Ref<boolean>;
  pendingImages: Ref<PendingImage[]>;
  pendingFile: Ref<PendingFile | null>;
  workingDir: Ref<string>;
  ensureSession: () => Promise<void>;
  addMessage: (role: string, text: string) => void;
  userMessageEntry: (id: number | null, content: string) => ChatMessage;
  reloadMessages: () => Promise<void>;
  loadFeedback: (sid: string) => Promise<void>;
  emit: (event: string, ...args: unknown[]) => void;
}

export function useResumableRun(p: ResumableRunParams): {
  activeRunId: Ref<string>;
  runStatus: Ref<string>;
  lastAppliedEventSeq: Ref<number>;
  reconnecting: Ref<boolean>;
  restoreActiveRun: (preferredSessionId: string) => Promise<void>;
  sendMessageStream: () => void;
  resetRunState: () => void;
} {
  const activeRunId = ref('');
  const runStatus = ref('');
  const lastAppliedEventSeq = ref(0);
  const reconnecting = ref(false);

  // 闭包变量(非 ref,同原 chat-panel.js 实现):每实例独立
  let currentES: ReturnType<typeof openResumableSse> | null = null;
  let restoringActiveRun = false;
  let runStore: ReturnType<typeof createStore> | null = null;

  async function ensureRunStore() {
    if (runStore) return runStore;
    let userKey = 'anonymous';
    try {
      const status = await fetch('/api/auth/status').then((r) => r.json());
      userKey = status.userId || status.username || userKey;
    } catch (e) { /* 使用 anonymous 隔离桶 */ }
    runStore = createStore(localStorage, userKey);
    return runStore;
  }

  async function queryActiveRuns() {
    const response = await fetch('/api/chat/runs/active');
    if (!response.ok) throw new Error('HTTP ' + response.status);
    const runs = await response.json();
    return Array.isArray(runs) ? runs : [];
  }

  async function saveRunMarker(extra?: Record<string, unknown>) {
    if (!activeRunId.value) return;
    const store = await ensureRunStore();
    store.put(Object.assign({
      runId: activeRunId.value,
      sessionId: p.sessionId.value,
      workingDir: p.workingDir.value,
      lastAppliedEventSeq: lastAppliedEventSeq.value,
      startedAt: Date.now(),
    }, extra || {}));
  }

  async function removeRunMarker(runId: string) {
    const store = await ensureRunStore();
    store.remove(runId);
  }

  function createResumableRenderer(msgIndex: number) {
    const chunks: string[] = [];
    let flushTimer: ReturnType<typeof setTimeout> | null = null;
    function flush() {
      if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
      const target = p.messages.value[msgIndex];
      if (!target || target.role !== 'agent') return;
      const output = chunks.join('\n');
      target.segments = output
        ? (isStreamJson(output) ? parseStreamJson(output) : [{ type: 'text', content: output }])
        : [];
      nextTick(() => {
        if (p.chatContainer.value) p.chatContainer.value.scrollTop = p.chatContainer.value.scrollHeight;
      });
    }
    return {
      recall: function (data: string) {
        const target = p.messages.value[msgIndex];
        if (!target || target.role !== 'agent') return;
        try { target.recall = JSON.parse(data); target.recallOpen = false; } catch (e) { /* ignore */ }
      },
      chunk: function (data: string) {
        chunks.push(data);
        if (!flushTimer) flushTimer = setTimeout(flush, 100);
      },
      flush: flush,
    };
  }

  function rememberEventCursor(event: { lastEventId?: string | number }) {
    const sequence = Number(event.lastEventId || 0);
    if (sequence > lastAppliedEventSeq.value) {
      lastAppliedEventSeq.value = sequence;
      saveRunMarker();
    }
  }

  async function finishResumableRun(runId: string, terminalData: string, renderer: ReturnType<typeof createResumableRenderer>) {
    renderer.flush();
    let terminal: { status?: string; errorMessage?: string } = {};
    try { terminal = JSON.parse(terminalData || '{}'); } catch (e) { terminal = {}; }
    runStatus.value = terminal.status || 'FAILED';
    p.sending.value = false;
    reconnecting.value = false;
    if (currentES) { currentES.close(); currentES = null; }
    await removeRunMarker(runId);
    activeRunId.value = '';
    lastAppliedEventSeq.value = 0;
    await p.reloadMessages();
    if (runStatus.value === 'SUCCEEDED') p.addMessage('system', '任务已完成');
    else if (runStatus.value === 'CANCELLED') p.addMessage('system', '任务已取消');
    else p.addMessage('error', terminal.errorMessage || '任务执行失败');
    p.emit('refresh-history');
  }

  async function handleExpiredCursor(runId: string, data: string) {
    let expired: { lastEventSeq?: number } = {};
    try { expired = JSON.parse(data || '{}'); } catch (e) { expired = {}; }
    await p.reloadMessages();
    p.addMessage('system', '早期流式片段已过期，已重新加载持久化消息');
    try {
      const statusResponse = await fetch('/api/chat/runs/' + encodeURIComponent(runId));
      if (!statusResponse.ok) throw new Error('HTTP ' + statusResponse.status);
      const status: { status?: string; lastEventSeq?: number } = await statusResponse.json();
      runStatus.value = status.status || '';
      if (['PENDING', 'RUNNING', 'CANCEL_REQUESTED'].indexOf(runStatus.value) >= 0) {
        const msgIndex = p.messages.value.length;
        p.messages.value.push({ id: null, role: 'agent', segments: [], recall: null, recallOpen: false });
        p.sending.value = true;
        subscribeResumableRun(runId, Number(expired.lastEventSeq || status.lastEventSeq || 0), msgIndex);
        return;
      }
    } catch (e) { /* 终态或已不可见，按快照收口 */ }
    p.sending.value = false;
    await removeRunMarker(runId);
    activeRunId.value = '';
  }

  function redirectToLogin() {
    fetch('/api/auth/status').then((r) => r.json()).then((status: { authenticated?: boolean; loginUrl?: string }) => {
      if (!status.authenticated && status.loginUrl) window.location.href = status.loginUrl;
    }).catch(() => {});
  }

  function subscribeResumableRun(runId: string, cursor: number, msgIndex: number) {
    const renderer = createResumableRenderer(msgIndex);
    const url = '/api/chat/runs/' + encodeURIComponent(runId) + '/events';
    const client = openResumableSse(url, { after: Math.max(0, Number(cursor) || 0) });
    currentES = client;
    client.addEventListener('run_status', (event: { lastEventId?: string | number; data: string }) => {
      rememberEventCursor(event);
      reconnecting.value = false;
      try { runStatus.value = JSON.parse(event.data).status || runStatus.value; } catch (e) { /* ignore */ }
    });
    client.addEventListener('recall', (event: { lastEventId?: string | number; data: string }) => {
      rememberEventCursor(event);
      reconnecting.value = false;
      renderer.recall(event.data);
    });
    client.addEventListener('chunk', (event: { lastEventId?: string | number; data: string }) => {
      rememberEventCursor(event);
      reconnecting.value = false;
      renderer.chunk(event.data);
    });
    client.addEventListener('ping', () => { reconnecting.value = false; });
    client.addEventListener('reconnecting', () => { reconnecting.value = true; });
    client.addEventListener('terminal', (event: { lastEventId?: string | number; data: string }) => {
      rememberEventCursor(event);
      finishResumableRun(runId, event.data, renderer);
    });
    client.addEventListener('cursor_expired', (event: { data: string }) => {
      currentES = null;
      handleExpiredCursor(runId, event.data);
    });
    client.addEventListener('unauthorized', redirectToLogin);
    client.addEventListener('fatal', (event: { data: string }) => {
      renderer.flush();
      p.sending.value = false;
      reconnecting.value = false;
      currentES = null;
      p.addMessage('error', event.data || 'SSE 连接错误');
      if (event.data === 'HTTP 404') {
        removeRunMarker(runId);
        activeRunId.value = '';
      }
    });
  }

  async function restoreActiveRun(preferredSessionId: string) {
    if (restoringActiveRun || p.sending.value || !p.workingDir.value) return;
    restoringActiveRun = true;
    try {
      const activeRuns = await queryActiveRuns();
      const store = await ensureRunStore();
      const localRuns = store.list();
      const activeIds: Record<string, boolean> = {};
      activeRuns.forEach((run: { runId: string }) => { activeIds[run.runId] = true; });
      Object.keys(localRuns).forEach((runId) => {
        if (!activeIds[runId]) store.remove(runId);
      });
      let selected = preferredSessionId
        ? activeRuns.find((run: { sessionId: string }) => run.sessionId === preferredSessionId)
        : null;
      if (!selected) {
        selected = selectActiveRun(activeRuns, localRuns, p.workingDir.value);
      }
      if (!selected) return;

      p.sessionId.value = selected.sessionId;
      p.resumeId.value = '';
      activeRunId.value = selected.runId;
      runStatus.value = selected.status;
      lastAppliedEventSeq.value = 0;
      p.sending.value = true;
      reconnecting.value = false;
      await p.reloadMessages();
      p.addMessage('system', '正在恢复运行中的任务');
      const msgIndex = p.messages.value.length;
      p.messages.value.push({ id: null, role: 'agent', segments: [], recall: null, recallOpen: false });
      await saveRunMarker({
        workingDir: selected.workingDir,
        startedAt: selected.startedAt || selected.createdAt || Date.now(),
      });
      p.emit('session-created', {
        sessionId: selected.sessionId,
        workingDir: selected.workingDir,
        agentType: selected.agentType,
      });
      // 新页面没有旧的局部渲染状态，必须从 0 回放完整保留窗口；同页断线由客户端按 cursor 续传。
      subscribeResumableRun(selected.runId, 0, msgIndex);
    } catch (e) {
      ElMessage.warning('恢复运行中任务失败: ' + ((e as Error).message || String(e)));
    } finally {
      restoringActiveRun = false;
    }
  }

  function newIdempotencyKey() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      return window.crypto.randomUUID();
    }
    return Date.now().toString(36) + '-' + Math.random().toString(36).substring(2);
  }

  async function submitResumableRun(idempotencyKey: string, payload: Record<string, unknown>) {
    let lastError: Error | null = null;
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        return await fetch('/api/chat/session/' + encodeURIComponent(p.sessionId.value) + '/runs', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': idempotencyKey,
          },
          body: JSON.stringify(payload),
        });
      } catch (e) {
        lastError = e as Error;
        if (attempt === 0) await new Promise((resolve) => setTimeout(resolve, 1000));
      }
    }
    throw lastError || new Error('提交任务失败');
  }

  async function sendMessageResumable() {
    if (!p.workingDir.value || !p.userInput.value.trim() || p.sending.value) return;
    try {
      await p.ensureSession();
    } catch (error) {
      ElMessage.error('创建会话失败: ' + (error as Error).message);
      return;
    }

    const baseText = p.userInput.value.trim();
    const imagePaths = p.pendingImages.value.map((img) => img.path);
    const filePath = p.pendingFile.value ? p.pendingFile.value.path : null;
    let message = baseText;
    if (imagePaths.length) message += '\n' + imagePaths.join('\n');
    if (filePath) message += '\n\n[附件清单]\n- ' + filePath;
    p.messages.value.push(p.userMessageEntry(null, message));
    p.userInput.value = '';
    p.pendingImages.value = [];
    p.pendingFile.value = null;
    p.sending.value = true;
    reconnecting.value = false;

    const msgIndex = p.messages.value.length;
    p.messages.value.push({ id: null, role: 'agent', segments: [], recall: null, recallOpen: false });
    const idempotencyKey = newIdempotencyKey();
    try {
      const response = await submitResumableRun(idempotencyKey, {
        message: message,
        resumeId: p.resumeId.value || null,
        recall: !!p.ragRecall.value,
      });
      if (!response.ok) {
        let error: { message?: string; code?: string } = {};
        try { error = await response.json(); } catch (e) { error = {}; }
        throw new Error(error.message || error.code || ('HTTP ' + response.status));
      }
      const submitted: { runId: string; status?: string } = await response.json();
      activeRunId.value = submitted.runId;
      runStatus.value = submitted.status || 'PENDING';
      lastAppliedEventSeq.value = 0;
      await saveRunMarker({ startedAt: Date.now() });
      subscribeResumableRun(submitted.runId, 0, msgIndex);
      p.emit('refresh-history');
    } catch (e) {
      p.sending.value = false;
      reconnecting.value = false;
      p.messages.value.splice(msgIndex, 1);
      await p.reloadMessages();
      ElMessage.error('发送失败: ' + ((e as Error).message || String(e)));
    }
  }

  const sendMessageStream = () => sendMessageResumable();

  // 供 clearConversation 调用:重置 run 状态 + 关闭 SSE 连接
  const resetRunState = () => {
    if (currentES) { currentES.close(); currentES = null; }
    activeRunId.value = '';
    runStatus.value = '';
    lastAppliedEventSeq.value = 0;
    reconnecting.value = false;
  };

  return {
    activeRunId, runStatus, lastAppliedEventSeq, reconnecting,
    restoreActiveRun, sendMessageStream, resetRunState,
  };
}