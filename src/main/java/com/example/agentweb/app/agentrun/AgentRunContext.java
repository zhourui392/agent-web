package com.example.agentweb.app.agentrun;

import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.shared.AgentType;
import lombok.Getter;

/**
 * Application-layer execution context for one agent run. It is not a domain aggregate.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Getter
public class AgentRunContext {

    private final String originalInput;
    private final RunForm runForm;
    private final SourceType sourceDomain;
    private final AgentType agentType;
    private final String workingDir;
    private final String env;
    private final String outputInstruction;
    private final WorkspaceContext workspaceContext;
    private final RunRecallPolicy recallPolicy;

    private AgentRunContext(Builder builder) {
        this.originalInput = builder.originalInput;
        this.runForm = builder.runForm == null ? RunForm.CUSTOM : builder.runForm;
        this.sourceDomain = builder.sourceDomain == null ? SourceType.GENERAL : builder.sourceDomain;
        this.agentType = builder.agentType;
        this.workingDir = builder.workingDir;
        this.env = builder.env;
        this.outputInstruction = builder.outputInstruction;
        this.workspaceContext = builder.workspaceContext;
        this.recallPolicy = builder.recallPolicy == null
                ? RunRecallPolicy.disabled()
                : builder.recallPolicy;
    }

    public AgentRunContext withWorkspaceContext(WorkspaceContext context) {
        return AgentRunContext.builder()
                .originalInput(originalInput)
                .runForm(runForm)
                .sourceDomain(sourceDomain)
                .agentType(agentType)
                .workingDir(workingDir)
                .env(env)
                .outputInstruction(outputInstruction)
                .workspaceContext(context)
                .recallPolicy(recallPolicy)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @author zhourui(V33215020)
     * @since 2026-06-13
     */
    public static class Builder {
        private String originalInput;
        private RunForm runForm;
        private SourceType sourceDomain;
        private AgentType agentType;
        private String workingDir;
        private String env;
        private String outputInstruction;
        private WorkspaceContext workspaceContext;
        private RunRecallPolicy recallPolicy;

        public Builder originalInput(String originalInput) {
            this.originalInput = originalInput;
            return this;
        }

        public Builder runForm(RunForm runForm) {
            this.runForm = runForm;
            return this;
        }

        public Builder sourceDomain(SourceType sourceDomain) {
            this.sourceDomain = sourceDomain;
            return this;
        }

        public Builder agentType(AgentType agentType) {
            this.agentType = agentType;
            return this;
        }

        public Builder workingDir(String workingDir) {
            this.workingDir = workingDir;
            return this;
        }

        public Builder env(String env) {
            this.env = env;
            return this;
        }

        public Builder outputInstruction(String outputInstruction) {
            this.outputInstruction = outputInstruction;
            return this;
        }

        public Builder workspaceContext(WorkspaceContext workspaceContext) {
            this.workspaceContext = workspaceContext;
            return this;
        }

        public Builder recallPolicy(RunRecallPolicy recallPolicy) {
            this.recallPolicy = recallPolicy;
            return this;
        }

        public AgentRunContext build() {
            return new AgentRunContext(this);
        }
    }
}
