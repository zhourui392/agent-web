/**
 * 剪贴板 lib (ES module): 浏览器挂 window.AgentClipboard, Node/Vitest 走 ES import。
 *
 * 抽出原因: copySegment/copyToClipboard 在 app.js / chat-panel.js / conversations.js /
 * refinery.js / share.html 五处逐字重复,收敛到单一真相源。
 *
 * - copyToClipboard(text): 返回 Promise<boolean>,不依赖 ElementPlus,供 shareSession 等复用。
 * - copySegment(text): UI 包装,复制后 ElMessage 提示,依赖全局 ElementPlus。
 *
 * 行为照搬原 app.js/chat-panel.js 实现: clipboard.writeText 失败时 copySegment 直接报错
 * (不走 fallback),copyToClipboard 则 fall through 到 textarea fallback -- 两者失败语义不同,
 * 故各自独立实现,不互相调用。
 */
export async function copyToClipboard(text) {
  if (navigator.clipboard && window.isSecureContext) {
    try { await navigator.clipboard.writeText(text); return true; } catch (e) { /* fall through */ }
  }
  try {
    var ta = document.createElement('textarea');
    ta.value = text; ta.style.position = 'fixed'; ta.style.left = '-9999px';
    document.body.appendChild(ta); ta.select();
    var ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  } catch (e) { return false; }
}

export async function copySegment(text) {
  if (!text) return;
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
    } else {
      var ta = document.createElement('textarea');
      ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
      document.body.appendChild(ta); ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
    }
    if (window.ElementPlus) window.ElementPlus.ElMessage.success('已复制');
  } catch (e) {
    if (window.ElementPlus) window.ElementPlus.ElMessage.error('复制失败');
  }
}

// 浏览器: 挂全局 window.AgentClipboard (兼容未改的消费者)
if (typeof window !== 'undefined') {
  window.AgentClipboard = {
    copyToClipboard: copyToClipboard,
    copySegment: copySegment
  };
}
