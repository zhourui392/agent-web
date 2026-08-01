package com.example.agentweb.app.workbench.handoff;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Interface 到 Application 的 Handoff 原始输入快照。
 *
 * <p>不包含 Domain 类型；Application 再将其转换为领域值对象命令。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseHandoffContentInput {

    private final String summary;
    private final List<DecisionInput> decisions;
    private final List<OpenQuestionInput> openQuestions;
    private final List<DocumentInput> pinnedFiles;
    private final List<String> referencedRunIds;

    public PhaseHandoffContentInput(
            String summary, List<DecisionInput> decisions,
            List<OpenQuestionInput> openQuestions,
            List<DocumentInput> pinnedFiles,
            List<String> referencedRunIds) {
        this.summary = summary;
        this.decisions = immutableCopy(decisions);
        this.openQuestions = immutableCopy(openQuestions);
        this.pinnedFiles = immutableCopy(pinnedFiles);
        this.referencedRunIds = immutableCopy(referencedRunIds);
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }

    /** Decision 原始字段。 */
    @Getter
    public static final class DecisionInput {
        private final String text;
        private final String rationale;

        public DecisionInput(String text, String rationale) {
            this.text = text;
            this.rationale = rationale;
        }
    }

    /** Open Question 原始字段。 */
    @Getter
    public static final class OpenQuestionInput {
        private final String text;
        private final String ownerHint;

        public OpenQuestionInput(String text, String ownerHint) {
            this.text = text;
            this.ownerHint = ownerHint;
        }
    }

    /** Pinned File 原始字段。 */
    @Getter
    public static final class DocumentInput {
        private final String repositoryKey;
        private final String relativePath;

        public DocumentInput(String repositoryKey, String relativePath) {
            this.repositoryKey = repositoryKey;
            this.relativePath = relativePath;
        }
    }
}
