/**
 * 分享会话 lib (UMD-lite): 浏览器挂 window.AgentShare, Node/Vitest 走 module.exports。
 *
 * 抽出原因: shareSession 在 app.js / chat-panel.js 两处近乎完全重复,仅 target 获取方式不同。
 * 收敛后消费者负责解析 target (string),lib 负责完整的 fetch + 复制 + 提示链路。
 *
 * 依赖: window.AgentClipboard.copyToClipboard (须在 clipboard.js 之后加载)。
 * 依赖: 全局 ElementPlus (ElMessage/ElMessageBox)。
 */
(function (root) {
  async function shareSession(target) {
    if (!target) return;
    try {
      var res = await fetch('/api/chat/session/' + encodeURIComponent(target) + '/share', { method: 'POST' });
      if (!res.ok) throw new Error(await res.text());
      var data = await res.json();
      var shareUrl = root.location.origin + root.withBase('/share.html?token=' + data.shareToken);
      var copied = await root.AgentClipboard.copyToClipboard(shareUrl);
      if (copied) {
        if (root.ElementPlus) root.ElementPlus.ElMessage.success('分享链接已复制到剪贴板');
      } else {
        if (root.ElementPlus) root.ElementPlus.ElMessageBox.alert(shareUrl, '分享链接（请手动复制）', {
          confirmButtonText: '关闭', customClass: 'share-link-dialog'
        });
      }
    } catch (e) {
      if (root.ElementPlus) root.ElementPlus.ElMessage.error('生成分享链接失败: ' + (e.message || '未知错误'));
    }
  }

  var api = { shareSession: shareSession };
  root.AgentShare = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
