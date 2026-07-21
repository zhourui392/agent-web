package com.example.agentweb.app.git;

/**
 * 当前用户 git 配置的只读视图，供 {@code GitConfigController} 回吐。凭证只返脱敏布尔，绝不回显。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public final class GitConfigView {

    private final String name;
    private final String email;
    private final boolean credentialConfigured;
    private final boolean readOnly;

    public GitConfigView(String name, String email, boolean credentialConfigured, boolean readOnly) {
        this.name = name == null ? "" : name;
        this.email = email == null ? "" : email;
        this.credentialConfigured = credentialConfigured;
        this.readOnly = readOnly;
    }

    /** 系统默认用户：只读、无可展示身份（用机器默认 git）。 */
    public static GitConfigView readOnly() {
        return new GitConfigView("", "", false, true);
    }

    /** 工号用户但尚未配置：可编辑、空表单。 */
    public static GitConfigView editableEmpty() {
        return new GitConfigView("", "", false, false);
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public boolean isCredentialConfigured() {
        return credentialConfigured;
    }

    public boolean isReadOnly() {
        return readOnly;
    }
}
