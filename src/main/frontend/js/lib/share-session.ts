/**
 * 分享会话 lib (ES module): 浏览器挂 window.AgentShare, Node/Vitest 走 ES import。
 *
 * 抽出原因: shareSession 在 app.js / chat-panel.js 两处近乎完全重复,仅 target 获取方式不同。
 * 收敛后消费者负责解析 target (string),lib 负责完整的 fetch + 复制 + 提示链路。
 *
 * 依赖全部经 ES import: copyToClipboard / ElMessage / ElMessageBox。
 */
import { copyToClipboard } from './clipboard.js';
import { ElMessageBox, ElMessage } from 'element-plus';

export async function shareSession(target: string): Promise<void> {
  if (!target) return;
  try {
    var res = await fetch('/api/chat/session/' + encodeURIComponent(target) + '/share', { method: 'POST' });
    if (!res.ok) throw new Error(await res.text());
    var data: any = await res.json();
    var shareUrl = window.location.origin + '/share.html?token=' + data.shareToken;
    var copied = await copyToClipboard(shareUrl);
    if (copied) {
      ElMessage.success('分享链接已复制到剪贴板');
    } else {
      ElMessageBox.alert(shareUrl, '分享链接（请手动复制）', {
        confirmButtonText: '关闭', customClass: 'share-link-dialog'
      });
    }
  } catch (e) {
    ElMessage.error('生成分享链接失败: ' + ((e as Error).message || '未知错误'));
  }
}
