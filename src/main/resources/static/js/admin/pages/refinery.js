/**
 * 管理后台「召回历史」页(MPA)。Knowledge Refinery 召回库存浏览 + 逐条删除,
 * 从主控制台聊天页迁移而来(召回开关仍留聊天页;此页只管历史库存的查看与维护)。
 *
 * 三态过滤:入库(含过期) / 可召回 / 已丢弃(低分)。前两态走 /api/refinery/chunks,
 * 末态走 /api/refinery/discarded(字段形状刻意对齐,同一张表渲染)。
 * 「查看来源」复用主控制台纯函数(window.AgentFormatters)拉来源会话消息,渲染同对话记录页。
 *
 * @author zhourui(V33215020)
 */
const { ref } = Vue;
const { renderMarkdown, parseUserMessage, imageUrl, formatTime, parseStreamJson, isStreamJson } = window.AgentFormatters;

bootstrapAdminApp({
  setup() {
    const ragList = ref([]);
    const ragTotal = ref(0);
    const ragPage = ref(1);
    const ragSize = ref(20);
    const ragStatus = ref('all');
    const ragLoading = ref(false);

    // ---- 来源会话详情抽屉(复用对话记录页渲染) ----
    const detailOpen = ref(false);
    const detailLoading = ref(false);
    const detail = ref(null);
    const detailTitle = ref('来源会话');

    const formatScore = (s) => (typeof s === 'number' ? s.toFixed(2) : '-');

    async function loadHistory() {
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
        ElementPlus.ElMessage.error('加载召回历史失败: ' + (e.message || '未知错误'));
      } finally {
        ragLoading.value = false;
      }
    }

    function onPageChange(newPage) {
      ragPage.value = newPage;
      loadHistory();
    }

    function onSizeChange(newSize) {
      ragSize.value = newSize;
      ragPage.value = 1;
      loadHistory();
    }

    function onStatusChange() {
      ragPage.value = 1;
      loadHistory();
    }

    /** 逐条硬删召回记录: 确认 → DELETE → 重拉当前页(空页则回退一页)。 */
    async function deleteChunk(chunk) {
      try {
        await ElementPlus.ElMessageBox.confirm(
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
        ElementPlus.ElMessage.success('已删除');
        // 删的是当前页最后一条且非首页时回退一页, 避免停在空页
        if (ragList.value.length === 1 && ragPage.value > 1) {
          ragPage.value -= 1;
        }
        loadHistory();
      } catch (e) {
        ElementPlus.ElMessage.error('删除失败: ' + (e.message || '未知错误'));
      }
    }

    const STATUS_LABELS = { ACTIVE: '可召回', DISCARDED: '已丢弃', ARCHIVED: '已过期' };
    const STATUS_TYPES = { ACTIVE: 'success', DISCARDED: 'warning', ARCHIVED: 'info' };
    const statusLabel = (s) => STATUS_LABELS[s] || '已过期';
    const statusTagType = (s) => STATUS_TYPES[s] || 'info';

    // 单条消息富化:助手 JSON → parsedSegments;用户 → bodyText + images;其余原样
    function enrichMessage(msg) {
      if (msg.role === 'assistant' && isStreamJson(msg.content)) {
        return Object.assign({}, msg, { parsedSegments: parseStreamJson(msg.content) });
      }
      if (msg.role === 'user') {
        const parsed = parseUserMessage(msg.content);
        return Object.assign({}, msg, { bodyText: parsed.text, images: parsed.images });
      }
      return Object.assign({}, msg);
    }

    async function copySegment(text) {
      if (!text) {
        return;
      }
      try {
        if (navigator.clipboard && window.isSecureContext) {
          await navigator.clipboard.writeText(text);
        } else {
          const ta = document.createElement('textarea');
          ta.value = text;
          ta.style.position = 'fixed';
          ta.style.opacity = '0';
          document.body.appendChild(ta);
          ta.select();
          document.execCommand('copy');
          document.body.removeChild(ta);
        }
        ElementPlus.ElMessage.success('已复制');
      } catch (e) {
        ElementPlus.ElMessage.error('复制失败');
      }
    }

    /** 查看来源会话:走 admin 跨用户对话详情端点;诊断来源/已删会话会 404,友好提示。 */
    async function viewSource(sessionId) {
      if (!sessionId) {
        ElementPlus.ElMessage.info('该记录无来源会话');
        return;
      }
      detailOpen.value = true;
      detailLoading.value = true;
      detail.value = null;
      detailTitle.value = '来源会话';
      try {
        const resp = await fetch('/api/metrics/conversations/' + encodeURIComponent(sessionId));
        if (!resp.ok) {
          ElementPlus.ElMessage.warning('来源会话不存在或非对话来源(如诊断任务)');
          detailOpen.value = false;
          return;
        }
        const data = await resp.json();
        data.messages = Array.isArray(data.messages) ? data.messages.map(enrichMessage) : [];
        detail.value = data;
        detailTitle.value = data.record.title || '来源会话';
      } catch (e) {
        ElementPlus.ElMessage.error('加载来源会话失败: ' + e);
        detailOpen.value = false;
      } finally {
        detailLoading.value = false;
      }
    }

    function fmtTime(iso) {
      if (!iso) {
        return '—';
      }
      return String(iso).replace('T', ' ').replace(/\..*$/, '').replace('Z', '').slice(0, 19);
    }

    const ROLE_LABELS = { user: '用户', assistant: '助手', system: '系统' };
    const roleLabel = (r) => ROLE_LABELS[r] || r;

    return {
      onReady: loadHistory,
      ragList, ragTotal, ragPage, ragSize, ragStatus, ragLoading,
      loadHistory, onPageChange, onSizeChange, onStatusChange, deleteChunk,
      formatScore, statusLabel, statusTagType,
      detailOpen, detailLoading, detail, detailTitle, viewSource, copySegment,
      fmtTime, roleLabel, renderMarkdown, imageUrl, formatTime
    };
  }
});
