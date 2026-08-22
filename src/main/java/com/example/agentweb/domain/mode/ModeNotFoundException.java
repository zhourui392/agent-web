package com.example.agentweb.domain.mode;

/**
 * 模式不存在或对当前用户不可见的统一语义。
 *
 * @author alex
 * @since 2026-08-20
 */
public class ModeNotFoundException extends RuntimeException {

    private final String modeId;

    public ModeNotFoundException(String modeId) {
        super("Chat mode not found: " + modeId);
        this.modeId = modeId;
    }

    public String getModeId() {
        return modeId;
    }
}
