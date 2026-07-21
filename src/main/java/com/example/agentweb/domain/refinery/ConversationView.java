package com.example.agentweb.domain.refinery;

import lombok.Getter;
import com.example.agentweb.domain.shared.AgentType;

import java.util.Collections;
import java.util.List;

/**
 * 上游聚合 (ChatSession / DiagnoseTask / ...) 投递进 RAG 子域的统一入参值对象.
 *
 * <p>边界规则: RAG 子域只看 ConversationView, 不 import 任何上游聚合类.</p>
 *
 * <p>不变量校验集中在 {@link Builder#build()}: sourceId / sourceType / agentType / workingDir
 * 必填, turns 至少 1 条; env / verdict 缺省走默认值.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-02
 */
public final class ConversationView {

    public static final String DEFAULT_ENV = "unknown";

    @Getter
    private final String sourceId;
    @Getter
    private final SourceType sourceType;
    @Getter
    private final AgentType agentType;
    @Getter
    private final String workingDir;
    @Getter
    private final String env;
    @Getter
    private final VerdictSignal verdict;
    @Getter
    private final List<ConversationTurn> turns;

    private ConversationView(Builder b) {
        this.sourceId = b.sourceId;
        this.sourceType = b.sourceType;
        this.agentType = b.agentType;
        this.workingDir = b.workingDir;
        this.env = b.env;
        this.verdict = b.verdict;
        this.turns = b.turns;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String sourceId;
        private SourceType sourceType;
        private AgentType agentType;
        private String workingDir;
        private String env;
        private VerdictSignal verdict;
        private List<ConversationTurn> turns;

        public Builder sourceId(String v) { this.sourceId = v; return this; }
        public Builder sourceType(SourceType v) { this.sourceType = v; return this; }
        public Builder agentType(AgentType v) { this.agentType = v; return this; }
        public Builder workingDir(String v) { this.workingDir = v; return this; }
        public Builder env(String v) { this.env = v; return this; }
        public Builder verdict(VerdictSignal v) { this.verdict = v; return this; }
        public Builder turns(List<ConversationTurn> v) { this.turns = v; return this; }

        public ConversationView build() {
            requireNonBlank(sourceId, "sourceId");
            requireNonNull(sourceType, "sourceType");
            requireNonNull(agentType, "agentType");
            requireNonNull(workingDir, "workingDir");
            if (turns == null || turns.isEmpty()) {
                throw new IllegalArgumentException("turns must not be empty");
            }
            if (env == null || env.trim().isEmpty()) {
                env = DEFAULT_ENV;
            }
            if (verdict == null) {
                verdict = VerdictSignal.NONE;
            }
            turns = Collections.unmodifiableList(new java.util.ArrayList<>(turns));
            return new ConversationView(this);
        }

        private static void requireNonBlank(String value, String name) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }

        private static void requireNonNull(Object value, String name) {
            if (value == null) {
                throw new IllegalArgumentException(name + " must not be null");
            }
        }
    }
}
