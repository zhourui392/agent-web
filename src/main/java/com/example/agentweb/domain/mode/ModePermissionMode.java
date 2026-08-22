package com.example.agentweb.domain.mode;

/**
 * Claude Code CLI {@code --permission-mode} 的合法取值。
 *
 * <p>CLI 2.1.237 实测无 {@code default} 值——缺省语义由"不传该 flag"表达，
 * 对应本枚举在模式中为 {@code null}。</p>
 *
 * @author alex
 * @since 2026-08-20
 */
public enum ModePermissionMode {

    ACCEPT_EDITS("acceptEdits"),
    AUTO("auto"),
    BYPASS_PERMISSIONS("bypassPermissions"),
    MANUAL("manual"),
    DONT_ASK("dontAsk"),
    PLAN("plan");

    private final String cliValue;

    ModePermissionMode(String cliValue) {
        this.cliValue = cliValue;
    }

    public String cliValue() {
        return cliValue;
    }

    /**
     * 从 CLI 字符串解析；空值返回 {@code null} 表达"不传 flag"，
     * 非法值抛 {@link IllegalArgumentException}。
     */
    public static ModePermissionMode fromCliValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim();
        for (ModePermissionMode mode : values()) {
            if (mode.cliValue.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unsupported permission mode: " + value);
    }
}
