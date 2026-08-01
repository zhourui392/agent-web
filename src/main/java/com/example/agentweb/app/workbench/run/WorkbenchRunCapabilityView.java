package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.capability.RejectedCapability;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RunRepositoryScopeFact;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workspace.RepositoryScope;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单次 Run 实际冻结的 Rules、Skills 与 MCP 安全追溯视图。
 *
 * <p>有意不包含 Rule 正文、Prompt 正文、MCP 命令、参数、环境变量、
 * Credential Reference 或 Secret。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunCapabilityView {

    private final String runId;
    private final String workbenchId;
    private final WorkbenchPhase phase;
    private final RunMode runMode;
    private final long createdAt;
    private final long overrideVersion;
    private final String policyVersion;
    private final String profileId;
    private final String profileVersion;
    private final String profileHash;
    private final String bindingHash;
    private final String runtimeCompatibility;
    private final String repositoryScopeHash;
    private final String primaryRepositoryKey;
    private final List<RepositoryView> repositories;
    private final List<RuleView> rules;
    private final List<SkillView> skills;
    private final List<McpServerView> mcpServers;
    private final List<RejectedView> rejected;

    private WorkbenchRunCapabilityView(
            WorkbenchRunSnapshot snapshot, RepositoryScope repositoryScope) {
        ResolvedCapabilityBinding binding = snapshot.getCapabilityBinding();
        this.runId = snapshot.getRunId();
        this.workbenchId = snapshot.getWorkbenchId().getValue();
        this.phase = snapshot.getPhase();
        this.runMode = snapshot.getRunMode();
        this.createdAt = snapshot.getCreatedAt().toEpochMilli();
        this.overrideVersion = snapshot.getOverrideVersion() == null
                ? 0L : snapshot.getOverrideVersion().longValue();
        this.policyVersion = binding.getPolicyVersion();
        this.profileId = binding.getProfileId();
        this.profileVersion = binding.getProfileVersion();
        this.profileHash = binding.getProfileHash();
        this.bindingHash = binding.getBindingHash();
        this.runtimeCompatibility = binding.getRuntimeCompatibility();
        this.repositoryScopeHash = snapshot.getRepositoryScopeHash();
        this.primaryRepositoryKey = repositoryScope.getPrimaryRepositoryKey();
        this.repositories = repositoryViews(
                snapshot.repositoryScopeFacts(repositoryScope));
        this.rules = ruleViews(binding.getRules());
        this.skills = skillViews(binding.getSkills());
        this.mcpServers = mcpViews(binding.getMcpServers());
        this.rejected = rejectedViews(binding.getRejected());
    }

    public static WorkbenchRunCapabilityView from(
            WorkbenchRunSnapshot snapshot,
            RepositoryScope repositoryScope) {
        if (snapshot == null || repositoryScope == null) {
            throw new IllegalArgumentException(
                    "workbench run snapshot and repository scope are required");
        }
        return new WorkbenchRunCapabilityView(snapshot, repositoryScope);
    }

    private static List<RepositoryView> repositoryViews(
            List<RunRepositoryScopeFact> facts) {
        List<RepositoryView> views =
                new ArrayList<RepositoryView>(facts.size());
        for (RunRepositoryScopeFact fact : facts) {
            views.add(new RepositoryView(
                    fact.getRepositoryKey(), fact.getRelativePath(),
                    fact.isPrimary(), fact.getAccess().name()));
        }
        return Collections.unmodifiableList(views);
    }

    private static List<RuleView> ruleViews(
            List<ResolvedRuleBinding> bindings) {
        List<RuleView> views = new ArrayList<RuleView>(bindings.size());
        for (ResolvedRuleBinding binding : bindings) {
            views.add(new RuleView(
                    binding.getId(), binding.getVersion(), binding.getSource(),
                    binding.getContentHash(), binding.isMandatory(),
                    binding.getSafeSummary()));
        }
        return Collections.unmodifiableList(views);
    }

    private static List<SkillView> skillViews(
            List<ResolvedSkillBinding> bindings) {
        List<SkillView> views = new ArrayList<SkillView>(bindings.size());
        for (ResolvedSkillBinding binding : bindings) {
            views.add(new SkillView(
                    binding.getId(), binding.getVersion(), binding.getSource(),
                    binding.getPackageHash(), binding.getTrustTier()));
        }
        return Collections.unmodifiableList(views);
    }

    private static List<McpServerView> mcpViews(
            List<ResolvedMcpServerBinding> bindings) {
        List<McpServerView> views =
                new ArrayList<McpServerView>(bindings.size());
        for (ResolvedMcpServerBinding binding : bindings) {
            views.add(new McpServerView(
                    binding.getId(), binding.getVersion(),
                    binding.getDefinitionHash(), binding.getAccess().name(),
                    binding.getTransport()));
        }
        return Collections.unmodifiableList(views);
    }

    private static List<RejectedView> rejectedViews(
            List<RejectedCapability> bindings) {
        List<RejectedView> views =
                new ArrayList<RejectedView>(bindings.size());
        for (RejectedCapability binding : bindings) {
            views.add(new RejectedView(
                    binding.getId(), binding.getReasonCode()));
        }
        return Collections.unmodifiableList(views);
    }

    @Getter
    public static final class RepositoryView {
        private final String repositoryKey;
        private final String relativePath;
        private final boolean primary;
        private final String access;

        private RepositoryView(
                String repositoryKey, String relativePath,
                boolean primary, String access) {
            this.repositoryKey = repositoryKey;
            this.relativePath = relativePath;
            this.primary = primary;
            this.access = access;
        }
    }

    @Getter
    public static final class RuleView {
        private final String id;
        private final String version;
        private final String source;
        private final String contentHash;
        private final boolean mandatory;
        private final String safeSummary;

        private RuleView(
                String id, String version, String source,
                String contentHash, boolean mandatory,
                String safeSummary) {
            this.id = id;
            this.version = version;
            this.source = source;
            this.contentHash = contentHash;
            this.mandatory = mandatory;
            this.safeSummary = safeSummary;
        }
    }

    @Getter
    public static final class SkillView {
        private final String id;
        private final String version;
        private final String source;
        private final String packageHash;
        private final String trustTier;

        private SkillView(
                String id, String version, String source,
                String packageHash, String trustTier) {
            this.id = id;
            this.version = version;
            this.source = source;
            this.packageHash = packageHash;
            this.trustTier = trustTier;
        }
    }

    @Getter
    public static final class McpServerView {
        private final String id;
        private final String version;
        private final String definitionHash;
        private final String access;
        private final String transport;

        private McpServerView(
                String id, String version, String definitionHash,
                String access, String transport) {
            this.id = id;
            this.version = version;
            this.definitionHash = definitionHash;
            this.access = access;
            this.transport = transport;
        }
    }

    @Getter
    public static final class RejectedView {
        private final String id;
        private final String reasonCode;

        private RejectedView(String id, String reasonCode) {
            this.id = id;
            this.reasonCode = reasonCode;
        }
    }
}
