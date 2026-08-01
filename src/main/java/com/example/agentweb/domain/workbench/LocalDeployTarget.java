package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 只引用管理员版本化模板的本地部署目标，不接受任意 shell 字符串。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class LocalDeployTarget implements HighImpactOperationTarget {

    public enum Environment {
        LOCAL
    }

    private final HighImpactOperationType type = HighImpactOperationType.LOCAL_DEPLOY;
    private final String templateId;
    private final String templateVersion;
    private final String templateHash;
    private final List<String> repositoryTargets;
    private final Environment environment;
    private final String expectedWorkspaceStateHash;
    private final String rollbackSummary;
    private final String payloadHash;

    private LocalDeployTarget(String templateId, String templateVersion,
                              String templateHash, List<String> repositoryTargets,
                              String expectedWorkspaceStateHash, String rollbackSummary) {
        this.templateId = DomainText.require(templateId, "deploy template id", 128);
        this.templateVersion = DomainText.require(
                templateVersion, "deploy template version", 128);
        this.templateHash = DomainText.requireSha256(
                templateHash, "deploy template hash");
        this.repositoryTargets = normalizeRepositories(repositoryTargets);
        this.environment = Environment.LOCAL;
        this.expectedWorkspaceStateHash = DomainText.requireSha256(
                expectedWorkspaceStateHash, "deploy expected workspace state hash");
        this.rollbackSummary = WorkbenchText.requireUntrustedText(
                rollbackSummary, "deploy rollback summary", 2000);
        this.payloadHash = HighImpactTargetSupport.payloadHash(
                type.name(), canonical -> appendPayload(canonical));
    }

    public static LocalDeployTarget create(
            String templateId, String templateVersion, String templateHash,
            List<String> repositoryTargets, String expectedWorkspaceStateHash,
            String rollbackSummary) {
        return new LocalDeployTarget(
                templateId, templateVersion, templateHash, repositoryTargets,
                expectedWorkspaceStateHash, rollbackSummary);
    }

    @Override
    public String requestedPayloadHash() {
        return payloadHash;
    }

    @Override
    public String expectedStateBinding() {
        return expectedWorkspaceStateHash;
    }

    @Override
    public Set<String> repositoryKeys() {
        return Collections.unmodifiableSet(new HashSet<String>(repositoryTargets));
    }

    @Override
    public boolean executionPermanentlyUnavailable() {
        return false;
    }

    private void appendPayload(StringBuilder canonical) {
        CanonicalHashing.appendFramed(canonical, "templateId", templateId);
        CanonicalHashing.appendFramed(canonical, "templateVersion", templateVersion);
        CanonicalHashing.appendFramed(canonical, "templateHash", templateHash);
        for (String repository : repositoryTargets) {
            CanonicalHashing.appendFramed(canonical, "repositoryKey", repository);
        }
        CanonicalHashing.appendFramed(canonical, "environment", environment);
        CanonicalHashing.appendFramed(
                canonical, "expectedWorkspaceStateHash", expectedWorkspaceStateHash);
    }

    private static List<String> normalizeRepositories(List<String> repositories) {
        if (repositories == null || repositories.isEmpty() || repositories.contains(null)) {
            throw new IllegalArgumentException(
                    "local deploy must target at least one repository");
        }
        List<String> normalized = new ArrayList<String>();
        Set<String> unique = new HashSet<String>();
        for (String repository : repositories) {
            String key = HighImpactTargetSupport.repositoryKey(repository);
            if (!unique.add(key)) {
                throw new IllegalArgumentException(
                        "local deploy repository targets must not contain duplicates");
            }
            normalized.add(key);
        }
        Collections.sort(normalized);
        return Collections.unmodifiableList(normalized);
    }
}
