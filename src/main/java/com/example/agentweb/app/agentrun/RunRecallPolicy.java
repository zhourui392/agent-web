package com.example.agentweb.app.agentrun;

import com.example.agentweb.domain.refinery.SourceType;
import lombok.Getter;

/**
 * Recall policy derived from run form, source domain and entry parameters.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Getter
public class RunRecallPolicy {

    private static final int DEFAULT_TOP_K = 8;

    private final boolean workspaceContextEnabled;
    private final boolean workspaceKnowledgeEnabled;
    private final boolean historicalRagEnabled;
    private final SourceType historicalSourceFilter;
    private final int topK;
    private final boolean persistObservation;

    private RunRecallPolicy(Builder builder) {
        this.workspaceContextEnabled = builder.workspaceContextEnabled;
        this.workspaceKnowledgeEnabled = builder.workspaceKnowledgeEnabled;
        this.historicalRagEnabled = builder.historicalRagEnabled;
        this.historicalSourceFilter = builder.historicalSourceFilter;
        this.topK = builder.topK <= 0 ? DEFAULT_TOP_K : builder.topK;
        this.persistObservation = builder.persistObservation;
    }

    public static RunRecallPolicy disabled() {
        return builder()
                .workspaceContextEnabled(false)
                .workspaceKnowledgeEnabled(false)
                .historicalRagEnabled(false)
                .topK(DEFAULT_TOP_K)
                .persistObservation(true)
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
        private boolean workspaceContextEnabled = true;
        private boolean workspaceKnowledgeEnabled = true;
        private boolean historicalRagEnabled;
        private SourceType historicalSourceFilter;
        private int topK = DEFAULT_TOP_K;
        private boolean persistObservation = true;

        public Builder workspaceContextEnabled(boolean workspaceContextEnabled) {
            this.workspaceContextEnabled = workspaceContextEnabled;
            return this;
        }

        public Builder workspaceKnowledgeEnabled(boolean workspaceKnowledgeEnabled) {
            this.workspaceKnowledgeEnabled = workspaceKnowledgeEnabled;
            return this;
        }

        public Builder historicalRagEnabled(boolean historicalRagEnabled) {
            this.historicalRagEnabled = historicalRagEnabled;
            return this;
        }

        public Builder historicalSourceFilter(SourceType historicalSourceFilter) {
            this.historicalSourceFilter = historicalSourceFilter;
            return this;
        }

        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        public Builder persistObservation(boolean persistObservation) {
            this.persistObservation = persistObservation;
            return this;
        }

        public RunRecallPolicy build() {
            return new RunRecallPolicy(this);
        }
    }
}
