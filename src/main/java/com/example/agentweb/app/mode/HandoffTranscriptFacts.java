package com.example.agentweb.app.mode;

/**
 * 交接转录事实（读侧装配）：首轮目标 + 尾部消息 + 产出/修改文件清单。
 *
 * @author alex
 * @since 2026-08-20
 */
public record HandoffTranscriptFacts(
        String firstUserGoal,
        java.util.List<TranscriptMessage> tailMessages,
        java.util.List<TranscriptFileChange> changedFiles) {

    public record TranscriptMessage(String role, String content) {
    }

    public record TranscriptFileChange(String path, String changeType) {
    }
}
