package com.example.agentweb.app.workbench.handoff;

import com.example.agentweb.domain.workbench.Decision;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.OpenQuestion;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Handoff 人工编辑内容的不可变应用命令。
 *
 * <p>这里只做输入快照；文本、条数、Scope、重复引用和 Run 归属均由领域聚合校验。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseHandoffContentCommand {

    private final String summary;
    private final List<Decision> decisions;
    private final List<OpenQuestion> openQuestions;
    private final List<DocumentReference> pinnedFiles;
    private final List<String> referencedRunIds;

    public PhaseHandoffContentCommand(
            String summary, List<Decision> decisions,
            List<OpenQuestion> openQuestions,
            List<DocumentReference> pinnedFiles,
            List<String> referencedRunIds) {
        this.summary = summary;
        this.decisions = immutableCopy(decisions, "decisions");
        this.openQuestions = immutableCopy(openQuestions, "openQuestions");
        this.pinnedFiles = immutableCopy(pinnedFiles, "pinnedFiles");
        this.referencedRunIds = immutableCopy(
                referencedRunIds, "referencedRunIds");
    }

    public static PhaseHandoffContentCommand from(
            PhaseHandoffContentInput input) {
        Objects.requireNonNull(input, "input");
        List<Decision> decisions = new ArrayList<Decision>();
        for (PhaseHandoffContentInput.DecisionInput value
                : input.getDecisions()) {
            decisions.add(Decision.confirmed(
                    value.getText(), value.getRationale()));
        }
        List<OpenQuestion> questions = new ArrayList<OpenQuestion>();
        for (PhaseHandoffContentInput.OpenQuestionInput value
                : input.getOpenQuestions()) {
            questions.add(OpenQuestion.of(
                    value.getText(), value.getOwnerHint()));
        }
        List<DocumentReference> files = new ArrayList<DocumentReference>();
        for (PhaseHandoffContentInput.DocumentInput value
                : input.getPinnedFiles()) {
            files.add(DocumentReference.of(
                    value.getRepositoryKey(), value.getRelativePath()));
        }
        return new PhaseHandoffContentCommand(
                input.getSummary(), decisions, questions, files,
                input.getReferencedRunIds());
    }

    private static <T> List<T> immutableCopy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
