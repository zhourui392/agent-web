package com.example.agentweb.infra.cli;

import com.example.agentweb.infra.AgentCliProperties;

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

    private BuildContext(Builder builder) {
        this.config = builder.config;
        this.userMessage = builder.userMessage;
        this.resumeId = builder.resumeId;
        this.workingDir = builder.workingDir;
        this.model = builder.model;
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

        public BuildContext build() {
            return new BuildContext(this);
        }
    }
}
