package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实现 Attempt 基线到 Runtime 完成时的真实 Git 变化证据。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class WorkspaceChangeEvidence {

    private final WorkspaceBaseline baseline;
    private final WorkspaceBaseline current;
    private final List<ChangedFileEvidence> baselineFiles;
    private final List<ChangedFileEvidence> currentFiles;
    private final List<ChangedFileEvidence> files;

    public WorkspaceChangeEvidence(WorkspaceBaseline baseline, WorkspaceBaseline current) {
        this(baseline, current, difference(baseline, current));
    }

    public WorkspaceChangeEvidence(WorkspaceBaseline baseline, WorkspaceBaseline current,
                                   List<ChangedFileEvidence> files) {
        if (baseline == null || current == null || files == null || files.contains(null)
                || !baseline.belongsToSameRepository(current)) {
            throw new IllegalArgumentException("workspace change evidence is inconsistent");
        }
        this.baseline = baseline;
        this.current = current;
        this.baselineFiles = baseline.getFiles();
        this.currentFiles = current.getFiles();
        this.files = Collections.unmodifiableList(new ArrayList<ChangedFileEvidence>(files));
    }

    private static List<ChangedFileEvidence> difference(WorkspaceBaseline baseline,
                                                        WorkspaceBaseline current) {
        if (baseline == null || current == null) {
            throw new IllegalArgumentException("workspace change evidence is inconsistent");
        }
        Map<String, ChangedFileEvidence> remaining = new LinkedHashMap<String, ChangedFileEvidence>();
        for (ChangedFileEvidence file : baseline.getFiles()) {
            remaining.put(file.getPath(), file);
        }
        List<ChangedFileEvidence> changes = new ArrayList<ChangedFileEvidence>();
        for (ChangedFileEvidence file : current.getFiles()) {
            ChangedFileEvidence before = remaining.remove(file.getPath());
            if (before == null || !file.sameStateAs(before)) {
                changes.add(file);
            }
        }
        for (ChangedFileEvidence removed : remaining.values()) {
            changes.add(removed.removedFromBaseline());
        }
        changes.sort(java.util.Comparator.comparing(ChangedFileEvidence::getPath));
        return changes;
    }
}
