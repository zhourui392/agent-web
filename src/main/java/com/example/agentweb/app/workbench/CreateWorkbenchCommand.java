package com.example.agentweb.app.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workspace.RepositorySelection;
import lombok.Getter;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 创建 Workbench 的规范化应用命令。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class CreateWorkbenchCommand {

    private static final String HASH_SCHEMA = "workbench-create-request@3";

    private final String idempotencyKey;
    private final String title;
    private final String originalGoal;
    private final AgentType agentType;
    private final String environment;
    private final String workspaceRoot;
    private final RepositorySelection repositorySelection;
    private final List<String> stageDefinitionIdentifiers;
    private final long expectedStageCatalogVersion;
    private final boolean useWorktree;
    private final String requestHash;

    public CreateWorkbenchCommand(
            String idempotencyKey, String title, String originalGoal,
            AgentType agentType, String environment, String workspaceRoot,
            String primaryRepositoryKey, List<String> repositoryKeys,
            List<String> stageDefinitionIdentifiers,
            long expectedStageCatalogVersion, boolean useWorktree) {
        this.idempotencyKey = DomainText.require(
                idempotencyKey, "workbench creation idempotency key", 128);
        this.title = DomainText.require(title, "workbench title", 512);
        this.originalGoal = DomainText.require(
                originalGoal, "workbench original goal", 16000);
        if (agentType == null) {
            throw new IllegalArgumentException("workbench agent type must not be null");
        }
        this.agentType = agentType;
        this.environment = normalizeOptional(environment);
        this.workspaceRoot = normalizeAbsolutePath(workspaceRoot);
        this.repositorySelection = RepositorySelection.of(
                primaryRepositoryKey, repositoryKeys);
        this.stageDefinitionIdentifiers = normalizeStageDefinitions(
                stageDefinitionIdentifiers);
        if (expectedStageCatalogVersion < 1L) {
            throw new IllegalArgumentException(
                    "expected Stage Catalog version must be positive");
        }
        this.expectedStageCatalogVersion = expectedStageCatalogVersion;
        this.useWorktree = useWorktree;
        this.requestHash = computeRequestHash();
    }

    private String computeRequestHash() {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "schema", HASH_SCHEMA);
        CanonicalHashing.appendFramed(canonical, "title", title);
        CanonicalHashing.appendFramed(canonical, "originalGoal", originalGoal);
        CanonicalHashing.appendFramed(canonical, "agentType", agentType.name());
        CanonicalHashing.appendFramed(canonical, "environment", environment);
        CanonicalHashing.appendFramed(canonical, "workspaceRoot", workspaceRoot);
        CanonicalHashing.appendFramed(
                canonical, "primaryRepository",
                repositorySelection.getPrimaryRepositoryKey());
        for (String repositoryKey : repositorySelection.getRepositoryKeys()) {
            CanonicalHashing.appendFramed(
                    canonical, "repositoryKey", repositoryKey);
        }
        CanonicalHashing.appendFramed(canonical, "stageCatalogVersion",
                expectedStageCatalogVersion);
        CanonicalHashing.appendFramed(canonical, "useWorktree", useWorktree);
        for (String definitionIdentifier : stageDefinitionIdentifiers) {
            CanonicalHashing.appendFramed(
                    canonical, "stageDefinitionIdentifier", definitionIdentifier);
        }
        return CanonicalHashing.sha256(canonical.toString());
    }

    private static List<String> normalizeStageDefinitions(List<String> source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one Stage Definition must be selected");
        }
        List<String> normalized = new ArrayList<String>(source.size());
        for (String identifier : source) {
            normalized.add(DomainText.require(
                    identifier, "Stage Definition identifier", 128));
        }
        Collections.sort(normalized);
        return Collections.unmodifiableList(normalized);
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return DomainText.require(value, "workbench environment", 256);
    }

    private static String normalizeAbsolutePath(String value) {
        String required = DomainText.require(value, "workspace root", 4096);
        try {
            Path path = Paths.get(required);
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException("workspace root must be absolute");
            }
            return path.normalize().toString();
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("workspace root is invalid", ex);
        }
    }
}
