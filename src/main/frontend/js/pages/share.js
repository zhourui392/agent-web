/**
 * 分享页 entry。原先是 share.html 里的 inline module 脚本,依赖 Vue / ElementPlus /
 * window.AgentFormatters / window.withBase 全局;npm 化后全局不再存在,故抽成独立模块。
 *
 * 本次只把「读全局」换成 ES import,页内那些与 lib/formatters 重复的本地实现
 * (parseUserMessage / formatTime / parseStreamJson / isStreamJson / mapMessages) 一律原样保留:
 * 原注释明确本页刻意自包含,收敛去重属于另一件事,不在 npm 化里顺手做,以免行为漂移。
 *
 * @author zhourui(V33215020)
 */
import { createApp, ref, onMounted, nextTick } from 'vue';
import { ElMessage } from 'element-plus';
import 'element-plus/dist/index.css';
import { setupElementPlus } from '../element-plus-setup.js';
import { withBase } from '../base.js';
import { renderMarkdown } from '../lib/formatters.js';

const app = createApp({
  setup() {
    const loading = ref(true);
    const error = ref('');
    const session = ref(null);
    const messages = ref([]);
    const shareToken = ref('');
    const bodyRef = ref(null);

    const copySegment = async (text) => {
      if (!text) return;
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
        ElMessage.success('已复制');
      } catch (e) {
        ElMessage.error('复制失败');
      }
    };

    // 用户消息里以独立行追加的图片绝对路径,渲染时分离出来用 <el-image> 显示缩略图
    const IMAGE_PATH_RE = /^.+[\/\\][^\/\\]+\.(png|jpe?g|gif|webp|bmp)$/i;
    const parseUserMessage = (text) => {
      if (!text) return { text: '', images: [] };
      const textLines = [];
      const images = [];
      for (const line of String(text).split('\n')) {
        const trimmed = line.trim();
        if (IMAGE_PATH_RE.test(trimmed)) {
          images.push(trimmed);
        } else {
          textLines.push(line);
        }
      }
      return { text: textLines.join('\n').trim(), images: images };
    };
    const imageUrl = (absPath) => withBase('/api/share/'
      + encodeURIComponent(shareToken.value) + '/image?path=' + encodeURIComponent(absPath));

    const formatTime = (isoStr) => {
      if (!isoStr) return '';
      try {
        const d = new Date(isoStr);
        return d.toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
      } catch (e) { return isoStr; }
    };

    const parseStreamJson = (raw) => {
      if (!raw) return [];
      const segments = [];
      function appendText(text) {
        const last = segments.length > 0 ? segments[segments.length - 1] : null;
        if (last && last.type === 'text') { last.content += text; }
        else { segments.push({ type: 'text', content: text }); }
      }
      const lines = raw.split('\n');
      for (let i = 0; i < lines.length; i++) {
        const line = lines[i].trim();
        if (!line) continue;
        try {
          const json = JSON.parse(line);
          if (json.type === 'stream_event' && json.event) {
            const evt = json.event;
            if (evt.type === 'content_block_start' && evt.content_block) {
              if (evt.content_block.type === 'tool_use') {
                segments.push({ type: 'tool', name: evt.content_block.name, content: '', _expanded: false });
              }
            } else if (evt.type === 'content_block_delta' && evt.delta) {
              if (evt.delta.type === 'text_delta' && evt.delta.text) { appendText(evt.delta.text); }
              else if (evt.delta.type === 'input_json_delta' && evt.delta.partial_json) {
                for (let j = segments.length - 1; j >= 0; j--) {
                  if (segments[j].type === 'tool') { segments[j].content += evt.delta.partial_json; break; }
                }
              }
            }
          } else if (json.type === 'user' && json.message && json.message.content) {
            for (const block of json.message.content) {
              if (block.type === 'tool_result') {
                let result = '';
                if (json.tool_use_result && typeof json.tool_use_result === 'string') { result = json.tool_use_result; }
                else if (typeof block.content === 'string') { result = block.content; }
                if (result) {
                  if (result.length > 2000) { result = result.substring(0, 2000) + '\n... (truncated)'; }
                  let merged = false;
                  for (let j = segments.length - 1; j >= 0; j--) {
                    if (segments[j].type === 'tool') { segments[j].content = (segments[j].content || '') + '\n' + result; merged = true; break; }
                  }
                  if (!merged) { segments.push({ type: 'tool', name: 'Tool Result', content: result, _expanded: false }); }
                }
              }
            }
          } else if (json.type === 'result' && json.result) {
            const hasText = segments.some(s => s.type === 'text' && s.content.trim());
            if (!hasText) { segments.push({ type: 'text', content: json.result }); }
          }
        } catch (e) {}
      }
      return segments;
    };

    // 与 js/lib/formatters.js#isStreamJson 同款:CLI stderr 告警会混入 stdout 头部,
    // 不能只看首字符,要在头部窗口内找第一条带 type 字段的 JSON 行(本页刻意自包含,不引 lib)。
    const isStreamJson = (content) => {
      if (!content) return false;
      const lines = String(content).split('\n');
      let scanned = 0;
      for (let i = 0; i < lines.length && scanned < 10; i++) {
        const line = lines[i].trim();
        if (!line) continue;
        scanned++;
        if (line.charAt(0) !== '{') continue;
        try { return typeof JSON.parse(line).type === 'string'; } catch (e) {}
      }
      return false;
    };

    // 服务端消息 → 渲染模型 (onMounted 首屏 + 每轮续聊结束后刷新共用)
    const mapMessages = (rawMsgs) => (rawMsgs || []).map(msg => {
      let recall = null;
      if (msg.role === 'assistant' && msg.recall) {
        try { recall = JSON.parse(msg.recall); } catch (e) { recall = null; }
      }
      if (msg.role === 'assistant' && isStreamJson(msg.content)) {
        return Object.assign({}, msg, { parsedSegments: parseStreamJson(msg.content), recall: recall, recallOpen: false });
      }
      if (msg.role === 'user') {
        const parsed = parseUserMessage(msg.content);
        return Object.assign({}, msg, { bodyText: parsed.text, images: parsed.images });
      }
      return Object.assign({}, msg, { recall: recall, recallOpen: false });
    });

    const scrollToBottom = () => {
      nextTick(() => {
        if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight;
      });
    };

    const loadShared = async () => {
      const res = await fetch('/api/share/' + encodeURIComponent(shareToken.value));
      if (!res.ok) {
        throw new Error(res.status === 404 ? '分享链接无效或已过期' : '加载失败');
      }
      const data = await res.json();
      if (data.error) { throw new Error(data.error); }
      session.value = data;
      document.title = (data.title || '共享对话') + ' - Agent Q&A';
      messages.value = mapMessages(data.messages);
    };

    onMounted(async () => {
      const params = new URLSearchParams(window.location.search);
      const token = params.get('token');
      if (!token) {
        loading.value = false;
        error.value = '缺少分享 token';
        return;
      }
      shareToken.value = token;
      try {
        await loadShared();
      } catch (e) {
        error.value = e.message || '加载分享对话失败';
      } finally {
        loading.value = false;
      }
    });

    return { loading, error, session, messages, bodyRef,
             renderMarkdown, copySegment, imageUrl, formatTime };
  }
});
setupElementPlus(app);
app.mount('#app');
