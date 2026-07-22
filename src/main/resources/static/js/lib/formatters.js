/**
 * 前端纯函数 lib (UMD-lite): 浏览器挂 window.AgentFormatters, Node/Vitest 走 module.exports。
 *
 * 抽出原因: app.js 单文件 2000+ 行 setup() 闭包,内嵌纯函数没法独立单测。
 * 这些函数无副作用、不依赖 Vue 响应式,适合下沉到独立模块。
 *
 * 严格照搬 app.js 原实现 (formatSize / renderMarkdown / parseUserMessage /
 * imageUrl / formatTime / formatBeijingDateTime / escapeHtml),行为必须完全等价 —— 任何细微差异都会
 * 让 chat / diagnose / issue-log UI 出现回归。
 */
(function (root) {
  var IMAGE_PATH_RE = /^.+[\/\\][^\/\\]+\.(png|jpe?g|gif|webp|bmp)$/i;

  function formatSize(bytes) {
    if (bytes == null) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  function renderMarkdown(text) {
    if (!text) return '';
    var cleaned = text.replace(/\n{3,}/g, '\n\n');
    try {
      if (typeof marked !== 'undefined' && marked.parse
        && typeof DOMPurify !== 'undefined' && DOMPurify.sanitize) {
        var rendered = marked.parse(cleaned, { breaks: false });
        return DOMPurify.sanitize(rendered, {
          USE_PROFILES: { html: true },
          ALLOW_DATA_ATTR: false,
          ALLOW_UNKNOWN_PROTOCOLS: false,
          FORBID_TAGS: ['style', 'iframe', 'object', 'embed', 'form', 'input', 'button',
            'textarea', 'select', 'option']
        });
      }
    } catch (e) { /* fallback */ }
    // 净化器缺失/异常时 fail-closed：不返回 marked 生成的未信任 HTML。
    return escapeHtml(cleaned);
  }

  function parseUserMessage(text) {
    if (!text) return { text: '', images: [] };
    var textLines = [];
    var images = [];
    var lines = String(text).split('\n');
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      var trimmed = line.trim();
      if (IMAGE_PATH_RE.test(trimmed)) {
        images.push(trimmed);
      } else {
        textLines.push(line);
      }
    }
    return { text: textLines.join('\n').trim(), images: images };
  }

  function imageUrl(absPath) {
    // img src 不经 window.fetch,故在浏览器侧自行补上下文前缀;Node/Vitest 无 window 时原样返回。
    var raw = '/api/fs/image?path=' + encodeURIComponent(absPath);
    return (typeof window !== 'undefined' && window.withBase) ? window.withBase(raw) : raw;
  }

  function formatTime(isoStr) {
    if (!isoStr) return '';
    try {
      var d = new Date(isoStr);
      return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
    } catch (e) { return isoStr; }
  }

  function formatBeijingDateTime(isoStr) {
    if (!isoStr) return '';
    try {
      var d = new Date(isoStr);
      if (Number.isNaN(d.getTime())) return isoStr;
      var parts = new Intl.DateTimeFormat('zh-CN', {
        timeZone: 'Asia/Shanghai',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hourCycle: 'h23'
      }).formatToParts(d);
      var values = {};
      for (var i = 0; i < parts.length; i++) {
        if (parts[i].type !== 'literal') {
          values[parts[i].type] = parts[i].value;
        }
      }
      return values.year + '-' + values.month + '-' + values.day
        + ' ' + values.hour + ':' + values.minute + ':' + values.second;
    } catch (e) { return isoStr; }
  }

  function escapeHtml(text) {
    if (!text) return '';
    return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\n/g, '<br>');
  }

  /**
   * 判定 assistant 消息内容是否为 stream-json blob(应交给 parseStreamJson 渲染)。
   * CLI 的 stderr 告警(如 workspace 未信任的 "Ignoring N permissions.allow entries...")
   * 会经 redirectErrorStream 混入 stdout 头部,导致首行不是 JSON —— 因此不能只看
   * content.startsWith('{'),而要在头部窗口内找第一条带 type 字段的 JSON 行。
   * 窗口只扫头部:stderr 噪音只会出现在进程启动阶段;不扫全文,避免把正文里
   * 含 JSON 示例的纯文本消息误判(误判会让 parseStreamJson 丢弃全部正文)。
   */
  var STREAM_JSON_HEAD_SCAN_LINES = 10;

  function isStreamJson(content) {
    if (!content) return false;
    var lines = String(content).split('\n');
    var scanned = 0;
    for (var i = 0; i < lines.length && scanned < STREAM_JSON_HEAD_SCAN_LINES; i++) {
      var line = lines[i].trim();
      if (!line) continue;
      scanned++;
      if (line.charAt(0) !== '{') continue;
      try {
        return typeof JSON.parse(line).type === 'string';
      } catch (e) { /* 非法 JSON,继续扫后续行 */ }
    }
    return false;
  }

  /**
   * 把已落库的 stream-json blob 解析成渲染用的 segment 数组(text / tool / tool_result)。
   * 纯函数,无副作用,供 ChatPanel(恢复会话)、主控台历史抽屉、诊断详情共用。
   * 与 app.js 原内联实现行为等价 —— 任何差异都会让历史/诊断渲染回归。
   */
  function parseStreamJson(raw) {
    if (!raw) return [];
    var segments = [];

    function appendText(text) {
      var last = segments.length > 0 ? segments[segments.length - 1] : null;
      if (last && last.type === 'text') {
        last.content += text;
      } else {
        segments.push({ type: 'text', content: text });
      }
    }

    var lines = raw.split('\n');
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].trim();
      if (!line) continue;
      try {
        var json = JSON.parse(line);
        if (json.type === 'stream_event' && json.event) {
          var evt = json.event;
          if (evt.type === 'content_block_start' && evt.content_block) {
            if (evt.content_block.type === 'tool_use') {
              segments.push({ type: 'tool', name: evt.content_block.name, content: '' });
            }
          } else if (evt.type === 'content_block_delta' && evt.delta) {
            if (evt.delta.type === 'text_delta' && evt.delta.text) {
              appendText(evt.delta.text);
            } else if (evt.delta.type === 'input_json_delta' && evt.delta.partial_json) {
              for (var j = segments.length - 1; j >= 0; j--) {
                if (segments[j].type === 'tool') {
                  segments[j].content += evt.delta.partial_json;
                  break;
                }
              }
            }
          }
        } else if (json.type === 'user' && json.message && json.message.content) {
          for (var b = 0; b < json.message.content.length; b++) {
            var block = json.message.content[b];
            if (block.type === 'tool_result') {
              var result = '';
              if (json.tool_use_result && typeof json.tool_use_result === 'string') {
                result = json.tool_use_result;
              } else if (typeof block.content === 'string') {
                result = block.content;
              }
              if (result) {
                if (result.length > 2000) {
                  result = result.substring(0, 2000) + '\n... (共 ' + result.length + ' 字符，已截断)';
                }
                var merged = false;
                for (var k = segments.length - 1; k >= 0; k--) {
                  if (segments[k].type === 'tool') {
                    segments[k].content = (segments[k].content || '') + '\n' + result;
                    merged = true;
                    break;
                  }
                }
                if (!merged) {
                  segments.push({ type: 'tool', name: 'Tool Result', content: result });
                }
              }
            }
          }
        } else if (json.type === 'result' && json.result) {
          var hasText = segments.some(function (s) { return s.type === 'text' && s.content.trim(); });
          if (!hasText) {
            segments.push({ type: 'text', content: json.result });
          }
        }
      } catch (e) { /* skip non-JSON */ }
    }
    return segments;
  }

  var api = {
    IMAGE_PATH_RE: IMAGE_PATH_RE,
    formatSize: formatSize,
    renderMarkdown: renderMarkdown,
    parseUserMessage: parseUserMessage,
    imageUrl: imageUrl,
    formatTime: formatTime,
    formatBeijingDateTime: formatBeijingDateTime,
    escapeHtml: escapeHtml,
    parseStreamJson: parseStreamJson,
    isStreamJson: isStreamJson
  };

  // 浏览器: 挂全局 window.AgentFormatters
  root.AgentFormatters = api;
  // Node/Vitest: CommonJS 导出
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
})(typeof window !== 'undefined' ? window : globalThis);
