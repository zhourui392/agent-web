/**
 * 分享会话 lib (ES module): 浏览器挂 window.AgentShare, Node/Vitest 走 ES import。
 *
 * 抽出原因: shareSession 在 app.js / chat-panel.js 两处近乎完全重复,仅 target 获取方式不同。
 * 收敛后消费者负责解析 target (string),lib 负责完整的 fetch + 复制 + 提示链路。
 *
 * 依赖: copyToClipboard (ES import from clipboard.js)。
 * 依赖: 全局 ElementPlus (ElMessage/ElMessageBox); window.withBase (base.js 运行时注入)。
 */
import { copyToClipboard } from './clipboard.js';

export async function shareSession(target) {
  if (!target) return;
  try {
    var res = await fetch('/api/chat/session/' + encodeURIComponent(target) + '/share', { method: 'POST' });
    if (!res.ok) throw new Error(await res.text());
    var data = await res.json();
    var shareUrl = window.location.origin + window.withBase('/share.html?token=' + data.shareToken);
    var copied = await copyToClipboard(shareUrl);
    if (copied) {
      if (window.ElementPlus) window.ElementPlus.ElMessage.success('分享链接已复制到剪贴板');
    } else {
      if (window.ElementPlus) window.ElementPlus.ElMessageBox.alert(shareUrl, '分享链接（请手动复制）', {
        confirmButtonText: '关闭', customClass: 'share-link-dialog'
      });
    }
  } catch (e) {
    if (window.ElementPlus) window.ElementPlus.ElMessage.error('生成分享链接失败: ' + (e.message || '未知错误'));
  }
}

// 浏览器: 挂全局 window.AgentShare (兼容未改的消费者)
if (typeof window !== 'undefined') {
  window.AgentShare = { shareSession: shareSession };
}
