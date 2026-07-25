/**
 * 管理后台「对话」页(MPA)。承接诊断「继续对话」的 continue-as-chat 闭环续聊:
 * 诊断页把 {sessionId,resumeId,workingDir,agentType} 写入 sessionStorage 后跳到本页,
 * 本页同步读出(读后即清)→ 驱动内嵌 <chat-panel> resume(chat-panel onMounted 即按 initialSessionId 恢复)。
 * 无交接数据时显示空态提示。
 *
 * @author zhourui(V33215020)
 */
const { ref } = Vue;

bootstrapAdminApp({
  setup() {
    const chatWorkingDir = ref('');
    const chatAgentType = ref('CODEX');
    const chatSessionId = ref('');
    const chatResumeId = ref('');

    // 同步读交接数据(setup 内,早于 chat-panel 挂载),让 chat-panel 首帧即拿到 initialSessionId。
    try {
      const raw = sessionStorage.getItem('admin.chat.handoff');
      if (raw) {
        sessionStorage.removeItem('admin.chat.handoff');
        const meta = JSON.parse(raw);
        if (meta && meta.sessionId) {
          chatAgentType.value = meta.agentType || 'CODEX';
          chatResumeId.value = meta.resumeId || '';
          chatWorkingDir.value = meta.workingDir || '';
          chatSessionId.value = meta.sessionId;
        }
      }
    } catch (e) {
      // 忽略坏交接数据,退化成空态
    }

    return { chatWorkingDir, chatAgentType, chatSessionId, chatResumeId };
  }
}, { withChatPanel: true });
