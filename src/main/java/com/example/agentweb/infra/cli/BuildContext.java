package com.example.agentweb.infra.cli;

import com.example.agentweb.config.cli.AgentCliProperties;

/**
 * 拼装 CLI 命令所需的上下文。预留 {@code workingDir} / {@code model} 字段以便后续 Codex 实现使用。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
public final class BuildContext {

    private final AgentCliProperties.Client config;
    private final String userMessage;
    private final String resumeId;
    private final String workingDir;
    private final String model;
    private final String endpoint;
    private final String reasoningEffort;
    private final String appendSystemPrompt;
    private final String permissionMode;
    private final String mcpConfigPath;
    private final String capabilityDir;

    private BuildContext(Builder builder) {
        this.config = builder.config;
        this.userMessage = builder.userMessage;
        this.resumeId = builder.resumeId;
        this.workingDir = builder.workingDir;
        this.model = builder.model;
        this.endpoint = builder.endpoint;
        this.reasoningEffort = builder.reasoningEffort;
        this.appendSystemPrompt = builder.appendSystemPrompt;
        this.permissionMode = builder.permissionMode;
        this.mcpConfigPath = builder.mcpConfigPath;
        this.capabilityDir = builder.capabilityDir;
    }

    public AgentCliProperties.Client getConfig() {
        return config;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getResumeId() {
        return resumeId;
    }

    public String getWorkingDir() {
        return workingDir;
    }

    public String getModel() {
        return model;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    /** 模式默认提示词，映射 {@code --append-system-prompt}；null 表示不追加。 */
    public String getAppendSystemPrompt() {
        return appendSystemPrompt;
    }

    /** per-run permission mode（CLI 原始值）；null 表示按方言缺省处理。 */
    public String getPermissionMode() {
        return permissionMode;
    }

    /** 已物化的 MCP 配置文件路径，映射 {@code --mcp-config}；null 表示无。 */
    public String getMcpConfigPath() {
        return mcpConfigPath;
    }

    /** 已物化的命令/skills plugin 目录，映射 {@code --plugin-dir}；null 表示无。 */
    public String getCapabilityDir() {
        return capabilityDir;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 构造器。仅 {@code config} 与 {@code userMessage} 为必填，其余字段方言按需读取。
     */
    public static final class Builder {
        private AgentCliProperties.Client config;
        private String userMessage;
        private String resumeId;
        private String workingDir;
        private String model;
        private String endpoint;
        private String reasoningEffort;
        private String appendSystemPrompt;
        private String permissionMode;
        private String mcpConfigPath;
        private String capabilityDir;

        public Builder config(AgentCliProperties.Client value) {
            this.config = value;
            return this;
        }

        public Builder userMessage(String value) {
            this.userMessage = value;
            return this;
        }

        public Builder resumeId(String value) {
            this.resumeId = value;
            return this;
        }

        public Builder workingDir(String value) {
            this.workingDir = value;
            return this;
        }

        public Builder model(String value) {
            this.model = value;
            return this;
        }

        public Builder endpoint(String value) {
            this.endpoint = value;
            return this;
        }

        public Builder reasoningEffort(String value) {
            this.reasoningEffort = value;
            return this;
        }

        public Builder appendSystemPrompt(String value) {
            this.appendSystemPrompt = value;
            return this;
        }

        public Builder permissionMode(String value) {
            this.permissionMode = value;
            return this;
        }

        public Builder mcpConfigPath(String value) {
            this.mcpConfigPath = value;
            return this;
        }

        public Builder capabilityDir(String value) {
            this.capabilityDir = value;
            return this;
        }

        public BuildContext build() {
            return new BuildContext(this);
        }
    }
}
