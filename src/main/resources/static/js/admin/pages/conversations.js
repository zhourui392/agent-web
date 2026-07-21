/**
 * 管理后台「对话记录」页(MPA)。/api/metrics/conversations 分页列表 + 详情抽屉(admin 全量,跨用户)。
 * 消息详情复用主控制台纯函数(window.AgentFormatters);登录门 / 顶栏 / 侧栏由 AdminShell 承载。
 *
 * @author zhourui(V33215020)
 */
const { ref } = Vue;
const {
  renderMarkdown,
  parseUserMessage,
  imageUrl,
  formatTime,
  formatBeijingDateTime,
  parseStreamJson,
  isStreamJson
} = window.AgentFormatters;

bootstrapAdminApp({
  setup() {
    const conversations = ref([]);
    const convTotal = ref(0);
    const convPage = ref(1);
    const convSize = ref(20);
    const convKeyword = ref('');
    const convLoading = ref(false);

    // ---- 详情抽屉 ----
    const detailOpen = ref(false);
    const detailLoading = ref(false);
    const detail = ref(null);
    const detailTitle = ref('对话详情');

    async function loadConversations() {
      convLoading.value = true;
      try {
        const params = new URLSearchParams({ page: convPage.value, size: convSize.value });
        if (convKeyword.value.trim()) {
          params.set('keyword', convKeyword.value.trim());
        }
        const data = await fetch('/api/metrics/conversations?' + params.toString()).then((r) => r.json());
        conversations.value = Array.isArray(data.rows) ? data.rows : [];
        convTotal.value = data.total || 0;
      } catch (e) {
        ElementPlus.ElMessage.error('加载对话记录失败: ' + e);
      } finally {
        convLoading.value = false;
      }
    }

    function searchConversations() {
      convPage.value = 1;
      loadConversations();
    }

    function onPageChange(page) {
      convPage.value = page;
      loadConversations();
    }

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

    async function openDetail(sessionId) {
      detailOpen.value = true;
      detailLoading.value = true;
      detail.value = null;
      detailTitle.value = '对话详情';
      try {
        const resp = await fetch('/api/metrics/conversations/' + encodeURIComponent(sessionId));
        if (!resp.ok) {
          ElementPlus.ElMessage.error('会话不存在或已删除');
          detailOpen.value = false;
          return;
        }
        const data = await resp.json();
        data.messages = Array.isArray(data.messages) ? data.messages.map(enrichMessage) : [];
        detail.value = data;
        detailTitle.value = data.record.title || '对话详情';
      } catch (e) {
        ElementPlus.ElMessage.error('加载详情失败: ' + e);
        detailOpen.value = false;
      } finally {
        detailLoading.value = false;
      }
    }

    function fmtTime(iso) {
      if (!iso) {
        return '—';
      }
      return formatBeijingDateTime(iso) || '—';
    }

    const FEEDBACK_LABELS = { CORRECT: '正确', PARTIALLY_CORRECT: '部分正确', INCORRECT: '错误' };
    const FEEDBACK_TYPES = { CORRECT: 'success', PARTIALLY_CORRECT: 'warning', INCORRECT: 'danger' };
    const ROLE_LABELS = { user: '用户', assistant: '助手', system: '系统' };

    const feedbackLabel = (r) => FEEDBACK_LABELS[r] || r;
    const feedbackTagType = (r) => FEEDBACK_TYPES[r] || 'info';
    const roleLabel = (r) => ROLE_LABELS[r] || r;

    return {
      onReady: loadConversations,
      conversations, convTotal, convPage, convSize, convKeyword, convLoading,
      loadConversations, searchConversations, onPageChange,
      detailOpen, detailLoading, detail, detailTitle, openDetail, copySegment,
      fmtTime, feedbackLabel, feedbackTagType, roleLabel,
      renderMarkdown, imageUrl, formatTime
    };
  }
});
