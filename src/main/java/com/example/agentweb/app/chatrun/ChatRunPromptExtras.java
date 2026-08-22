package com.example.agentweb.app.chatrun;

/**
 * 模式会话追加到基础 prompt 之外的额外 part（HANDOFF / SELECTED_CAPABILITIES）。
 *
 * <p>全部字段可空；为空时 prompt 输出与既有 Chat 行为逐字节一致。</p>
 *
 * @author alex
 * @since 2026-08-20
 */
public final class ChatRunPromptExtras {

    private final String handoffFilePath;
    private final String handoffContent;
    private final String modeCapabilityAnnouncement;

    public ChatRunPromptExtras(String handoffFilePath, String handoffContent,
                               String modeCapabilityAnnouncement) {
        this.handoffFilePath = handoffFilePath;
        this.handoffContent = handoffContent;
        this.modeCapabilityAnnouncement = modeCapabilityAnnouncement;
    }

    public static ChatRunPromptExtras none() {
        return new ChatRunPromptExtras(null, null, null);
    }

    public boolean isEmpty() {
        return (handoffContent == null || handoffContent.isBlank())
                && (modeCapabilityAnnouncement == null || modeCapabilityAnnouncement.isBlank());
    }

    public String getHandoffFilePath() {
        return handoffFilePath;
    }

    public String getHandoffContent() {
        return handoffContent;
    }

    public String getModeCapabilityAnnouncement() {
        return modeCapabilityAnnouncement;
    }
}
