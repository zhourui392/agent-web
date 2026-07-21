package com.example.agentweb.app.agentrun;

import com.example.agentweb.domain.refinery.TrustTier;
import lombok.Getter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lightweight context discovered from a workspace. It intentionally does not model a full profile.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Getter
public class WorkspaceContext {

    private final Path workingDir;
    private final Path workspaceRoot;
    private final String name;
    private final String description;
    private final List<WorkspaceKnowledgeIndex> knowledgeIndexes;
    private final Map<String, WorkspaceGuardrail> guardrails;
    /** 召回可信度下限 (manifest recall.min_tier); null = 不限制 (非排障 workspace 现状). */
    private final TrustTier recallMinTier;

    public WorkspaceContext(Path workingDir,
                            Path workspaceRoot,
                            String name,
                            String description,
                            List<WorkspaceKnowledgeIndex> knowledgeIndexes,
                            Map<String, WorkspaceGuardrail> guardrails) {
        this(workingDir, workspaceRoot, name, description, knowledgeIndexes, guardrails, null);
    }

    public WorkspaceContext(Path workingDir,
                            Path workspaceRoot,
                            String name,
                            String description,
                            List<WorkspaceKnowledgeIndex> knowledgeIndexes,
                            Map<String, WorkspaceGuardrail> guardrails,
                            TrustTier recallMinTier) {
        this.workingDir = workingDir == null ? null : workingDir.toAbsolutePath().normalize();
        this.workspaceRoot = workspaceRoot == null ? this.workingDir : workspaceRoot.toAbsolutePath().normalize();
        this.name = trimToNull(name);
        this.description = trimToNull(description);
        this.knowledgeIndexes = immutableIndexes(knowledgeIndexes);
        this.guardrails = immutableGuardrails(guardrails);
        this.recallMinTier = recallMinTier;
    }

    public Optional<WorkspaceGuardrail> guardrailFor(String env) {
        if (env == null || env.trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(guardrails.get(env.trim()));
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("[Workspace Context]\n");
        if (workingDir != null) {
            sb.append("- workingDir: ").append(workingDir).append('\n');
        }
        if (workspaceRoot != null) {
            sb.append("- workspaceRoot: ").append(workspaceRoot).append('\n');
        }
        if (name != null) {
            sb.append("- name: ").append(name).append('\n');
        }
        if (knowledgeIndexes.isEmpty()) {
            sb.append("- knowledge indexes: none\n");
        } else {
            sb.append("- knowledge indexes:\n");
            for (WorkspaceKnowledgeIndex index : knowledgeIndexes) {
                sb.append("  - ").append(index.getRelativePath()).append('\n');
            }
        }
        sb.append("- knowledge pre-recall: enabled\n");
        sb.append("候选知识只作为线索，结论必须经过代码、日志、DB、配置或 trace 等证据验证。");
        return sb.toString();
    }

    private List<WorkspaceKnowledgeIndex> immutableIndexes(List<WorkspaceKnowledgeIndex> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<WorkspaceKnowledgeIndex>(source));
    }

    private Map<String, WorkspaceGuardrail> immutableGuardrails(Map<String, WorkspaceGuardrail> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, WorkspaceGuardrail>(source));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
