package com.example.agentweb.app.mode;

/**
 * 模式切换用例：单步同步完成"导出旧会话转录 + 建立绑定新模式的新会话"。
 *
 * @author alex
 * @since 2026-08-20
 */
public interface ModeSwitchAppService {

    ModeSwitchResult switchMode(String sessionId, String targetModeId, String idempotencyKey);

    /**
     * 切换结果：新会话 id 与交接文档 id。
     */
    record ModeSwitchResult(String newSessionId, String handoffDocumentId) {
    }
}
