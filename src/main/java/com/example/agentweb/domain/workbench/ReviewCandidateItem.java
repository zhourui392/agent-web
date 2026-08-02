package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Agent 提出的单条结构化 Review 建议；没有人工接受状态或修改授权语义。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ReviewCandidateItem {

    private static final String ITEM_ID_SCHEMA = "workbench-review-candidate-item@1";
    private static final int MAX_AFFECTED_FILES = 50;
    private static final int MAX_SUGGESTED_TESTS = 20;

    private final String itemId;
    private final String finding;
    private final String impact;
    private final String suggestedChange;
    private final List<DocumentReference> affectedFiles;
    private final List<String> suggestedTests;

    private ReviewCandidateItem(
            String finding, String impact, String suggestedChange,
            List<DocumentReference> affectedFiles,
            List<String> suggestedTests) {
        this.finding = WorkbenchText.requireUntrustedText(
                finding, "review candidate finding", 2000);
        this.impact = WorkbenchText.allowEmptyUntrustedText(
                impact, "review candidate impact", 4000);
        this.suggestedChange = WorkbenchText.allowEmptyUntrustedText(
                suggestedChange, "review candidate suggested change", 4000);
        this.affectedFiles = files(affectedFiles);
        this.suggestedTests = tests(suggestedTests);
        this.itemId = itemId();
    }

    public static ReviewCandidateItem propose(
            String finding, String impact, String suggestedChange,
            List<DocumentReference> affectedFiles,
            List<String> suggestedTests) {
        return new ReviewCandidateItem(
                finding, impact, suggestedChange,
                affectedFiles, suggestedTests);
    }

    private List<DocumentReference> files(
            List<DocumentReference> values) {
        if (values == null || values.contains(null)
                || values.size() > MAX_AFFECTED_FILES) {
            throw new IllegalArgumentException(
                    "review candidate affected files are invalid");
        }
        List<DocumentReference> result =
                new ArrayList<DocumentReference>(values);
        if (new HashSet<DocumentReference>(result).size() != result.size()) {
            throw new IllegalArgumentException(
                    "review candidate affected files must be unique");
        }
        result.sort(Comparator.naturalOrder());
        return Collections.unmodifiableList(result);
    }

    private List<String> tests(List<String> values) {
        if (values == null || values.contains(null)
                || values.size() > MAX_SUGGESTED_TESTS) {
            throw new IllegalArgumentException(
                    "review candidate suggested tests are invalid");
        }
        List<String> result = new ArrayList<String>(values.size());
        HashSet<String> unique = new HashSet<String>();
        for (String value : values) {
            String test = WorkbenchText.requireUntrustedText(
                    value, "review candidate suggested test", 1000);
            if (!unique.add(test)) {
                throw new IllegalArgumentException(
                        "review candidate suggested tests must be unique");
            }
            result.add(test);
        }
        return Collections.unmodifiableList(result);
    }

    private String itemId() {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(
                canonical, "schema", ITEM_ID_SCHEMA);
        CanonicalHashing.appendFramed(canonical, "finding", finding);
        CanonicalHashing.appendFramed(canonical, "impact", impact);
        CanonicalHashing.appendFramed(
                canonical, "suggestedChange", suggestedChange);
        for (DocumentReference file : affectedFiles) {
            CanonicalHashing.appendFramed(
                    canonical, "repositoryKey", file.getRepositoryKey());
            CanonicalHashing.appendFramed(
                    canonical, "relativePath", file.getRelativePath());
        }
        for (String test : suggestedTests) {
            CanonicalHashing.appendFramed(
                    canonical, "suggestedTest", test);
        }
        return CanonicalHashing.sha256(canonical.toString());
    }
}
