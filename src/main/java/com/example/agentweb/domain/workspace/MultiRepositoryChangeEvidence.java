package com.example.agentweb.domain.workspace;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 同一拓扑下两个 WorkspaceSnapshot 之间的多仓库变化证据。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class MultiRepositoryChangeEvidence {

    private final WorkspaceSnapshotReference baseline;
    private final WorkspaceSnapshotReference current;
    private final List<RepositoryChangeEvidence> repositories;

    private MultiRepositoryChangeEvidence(WorkspaceSnapshotReference baseline,
                                          WorkspaceSnapshotReference current,
                                          List<RepositoryChangeEvidence> repositories) {
        this.baseline = baseline;
        this.current = current;
        this.repositories = Collections.unmodifiableList(repositories);
    }

    public static MultiRepositoryChangeEvidence between(WorkspaceSnapshot baseline,
                                                        WorkspaceSnapshot current) {
        if (baseline == null || current == null) {
            throw new IllegalArgumentException("baseline and current snapshots are required");
        }
        if (!baseline.sameTopology(current)) {
            throw new IllegalArgumentException(
                    "workspace change evidence requires the same topology");
        }

        Map<String, RepositoryBaseline> before = index(baseline.getRepositories());
        Map<String, RepositoryBaseline> after = index(current.getRepositories());
        if (!before.keySet().equals(after.keySet())) {
            throw new IllegalArgumentException(
                    "workspace change evidence repository sets must match");
        }

        List<RepositoryChangeEvidence> changes = new ArrayList<RepositoryChangeEvidence>();
        List<String> keys = new ArrayList<String>(before.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            changes.add(new RepositoryChangeEvidence(before.get(key), after.get(key)));
        }
        changes.sort(Comparator.comparing(RepositoryChangeEvidence::getRepositoryKey));

        return new MultiRepositoryChangeEvidence(
                baseline.reference(), current.reference(), changes);
    }

    public boolean hasChanges() {
        for (RepositoryChangeEvidence evidence : repositories) {
            if (evidence.hasChanges()) {
                return true;
            }
        }
        return false;
    }

    public List<RepositoryChangeEvidence> changedRepositories() {
        List<RepositoryChangeEvidence> changed = new ArrayList<RepositoryChangeEvidence>();
        for (RepositoryChangeEvidence evidence : repositories) {
            if (evidence.hasChanges()) {
                changed.add(evidence);
            }
        }
        return Collections.unmodifiableList(changed);
    }

    private static Map<String, RepositoryBaseline> index(List<RepositoryBaseline> baselines) {
        Map<String, RepositoryBaseline> byKey = new LinkedHashMap<String, RepositoryBaseline>();
        for (RepositoryBaseline baseline : baselines) {
            byKey.put(baseline.getRepositoryKey(), baseline);
        }
        return byKey;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MultiRepositoryChangeEvidence)) {
            return false;
        }
        MultiRepositoryChangeEvidence that = (MultiRepositoryChangeEvidence) other;
        return baseline.equals(that.baseline)
                && current.equals(that.current)
                && repositories.equals(that.repositories);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseline, current, repositories);
    }
}
