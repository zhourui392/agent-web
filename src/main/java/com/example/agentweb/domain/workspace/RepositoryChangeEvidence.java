package com.example.agentweb.domain.workspace;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一个仓库在两次快照间的文件变化。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RepositoryChangeEvidence {

    private final String repositoryKey;
    private final RepositoryBaseline baseline;
    private final RepositoryBaseline current;
    private final List<ChangedFileEvidence> files;

    public RepositoryChangeEvidence(RepositoryBaseline baseline, RepositoryBaseline current) {
        this(baseline, current, difference(baseline, current));
    }

    public RepositoryChangeEvidence(RepositoryBaseline baseline, RepositoryBaseline current,
                                    List<ChangedFileEvidence> files) {
        if (baseline == null || current == null || files == null || files.contains(null)
                || !baseline.belongsToSameRepository(current)) {
            throw new IllegalArgumentException("repository change evidence is inconsistent");
        }
        this.repositoryKey = baseline.getRepositoryKey();
        this.baseline = baseline;
        this.current = current;
        this.files = Collections.unmodifiableList(new ArrayList<ChangedFileEvidence>(files));
    }

    public boolean hasChanges() {
        return !files.isEmpty()
                || !baseline.getHead().equals(current.getHead())
                || !baseline.getDiffHash().equals(current.getDiffHash());
    }

    private static List<ChangedFileEvidence> difference(RepositoryBaseline baseline,
                                                        RepositoryBaseline current) {
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RepositoryChangeEvidence)) {
            return false;
        }
        RepositoryChangeEvidence that = (RepositoryChangeEvidence) other;
        return repositoryKey.equals(that.repositoryKey)
                && baseline.equals(that.baseline)
                && current.equals(that.current)
                && files.equals(that.files);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repositoryKey, baseline, current, files);
    }
}
