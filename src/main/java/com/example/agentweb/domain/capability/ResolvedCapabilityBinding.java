package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 与具体消费方流程阶段无关的不可变能力解析结果。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ResolvedCapabilityBinding {

    public static final String HASH_SCHEMA = "resolved-capability-binding@1";

    private final String policyVersion;
    private final String profileId;
    private final String profileVersion;
    private final String profileHash;
    private final List<ResolvedRuleBinding> rules;
    private final List<ResolvedSkillBinding> skills;
    private final List<ResolvedMcpServerBinding> mcpServers;
    private final List<RejectedCapability> rejected;
    private final String runtimeCompatibility;
    private final String bindingHash;

    public ResolvedCapabilityBinding(String policyVersion, String profileId,
                                     String profileVersion, String profileHash,
                                     List<ResolvedRuleBinding> rules,
                                     List<ResolvedSkillBinding> skills,
                                     List<ResolvedMcpServerBinding> mcpServers,
                                     List<RejectedCapability> rejected,
                                     String runtimeCompatibility, String bindingHash) {
        this(policyVersion, profileId, profileVersion, profileHash,
                rules, skills, mcpServers, rejected, runtimeCompatibility,
                bindingHash, true);
    }

    private ResolvedCapabilityBinding(
            String policyVersion, String profileId,
            String profileVersion, String profileHash,
            List<ResolvedRuleBinding> rules,
            List<ResolvedSkillBinding> skills,
            List<ResolvedMcpServerBinding> mcpServers,
            List<RejectedCapability> rejected,
            String runtimeCompatibility, String persistedBindingHash,
            boolean verifyPersistedHash) {
        this.policyVersion = DomainText.require(policyVersion, "capability policy version", 120);
        this.profileId = DomainText.require(profileId, "capability profile id", 160);
        this.profileVersion = DomainText.require(profileVersion, "capability profile version", 80);
        this.profileHash = DomainText.requireSha256(profileHash, "capability profile hash");
        this.rules = immutableSortedUnique(
                rules, "resolved rules", ResolvedRuleBinding::getId,
                Comparator.comparing(ResolvedRuleBinding::getId));
        this.skills = immutableSortedUnique(
                skills, "resolved skills", ResolvedSkillBinding::getId,
                Comparator.comparing(ResolvedSkillBinding::getId));
        this.mcpServers = immutableSortedUnique(
                mcpServers, "resolved MCP servers", ResolvedMcpServerBinding::getId,
                Comparator.comparing(ResolvedMcpServerBinding::getId));
        this.rejected = immutableSortedUnique(
                rejected, "rejected capabilities", RejectedCapability::getId,
                Comparator.comparing(RejectedCapability::getId));
        this.runtimeCompatibility = DomainText.require(
                runtimeCompatibility, "runtime compatibility", 240);
        this.bindingHash = computeBindingHash();
        if (verifyPersistedHash) {
            String persisted = DomainText.requireSha256(
                    persistedBindingHash, "capability binding hash");
            if (!this.bindingHash.equals(persisted)) {
                throw new IllegalArgumentException(
                        "persisted capability binding hash does not match resolved facts");
            }
        }
    }

    public static ResolvedCapabilityBinding resolve(
            String policyVersion, String profileId,
            String profileVersion, String profileHash,
            List<ResolvedRuleBinding> rules,
            List<ResolvedSkillBinding> skills,
            List<ResolvedMcpServerBinding> mcpServers,
            List<RejectedCapability> rejected,
            String runtimeCompatibility) {
        return new ResolvedCapabilityBinding(
                policyVersion, profileId, profileVersion, profileHash,
                rules, skills, mcpServers, rejected, runtimeCompatibility,
                null, false);
    }

    public static ResolvedCapabilityBinding restore(
            String policyVersion, String profileId,
            String profileVersion, String profileHash,
            List<ResolvedRuleBinding> rules,
            List<ResolvedSkillBinding> skills,
            List<ResolvedMcpServerBinding> mcpServers,
            List<RejectedCapability> rejected,
            String runtimeCompatibility, String bindingHash) {
        return new ResolvedCapabilityBinding(
                policyVersion, profileId, profileVersion, profileHash,
                rules, skills, mcpServers, rejected, runtimeCompatibility,
                bindingHash, true);
    }

    private static <T> List<T> immutableSortedUnique(
            List<T> values, String name, Function<T, String> idExtractor,
            Comparator<T> comparator) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(name + " must not be null or contain null");
        }
        List<T> ordered = new ArrayList<T>(values);
        Collections.sort(ordered, comparator);
        Set<String> identifiers = new HashSet<String>();
        for (T value : ordered) {
            String identifier = idExtractor.apply(value);
            if (!identifiers.add(identifier)) {
                throw new IllegalArgumentException(
                        name + " must not contain duplicate id: " + identifier);
            }
        }
        return Collections.unmodifiableList(ordered);
    }

    private String computeBindingHash() {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "schema", HASH_SCHEMA);
        CanonicalHashing.appendFramed(canonical, "policyVersion", policyVersion);
        CanonicalHashing.appendFramed(canonical, "profileId", profileId);
        CanonicalHashing.appendFramed(canonical, "profileVersion", profileVersion);
        CanonicalHashing.appendFramed(canonical, "profileHash", profileHash);
        CanonicalHashing.appendFramed(
                canonical, "runtimeCompatibility", runtimeCompatibility);
        CanonicalHashing.appendFramed(canonical, "ruleCount", rules.size());
        for (ResolvedRuleBinding rule : rules) {
            CanonicalHashing.appendFramed(canonical, "ruleId", rule.getId());
            CanonicalHashing.appendFramed(canonical, "ruleVersion", rule.getVersion());
            CanonicalHashing.appendFramed(canonical, "ruleSource", rule.getSource());
            CanonicalHashing.appendFramed(
                    canonical, "ruleContentHash", rule.getContentHash());
            CanonicalHashing.appendFramed(
                    canonical, "ruleMandatory", rule.isMandatory());
            CanonicalHashing.appendFramed(
                    canonical, "ruleSafeSummary", rule.getSafeSummary());
        }
        CanonicalHashing.appendFramed(canonical, "skillCount", skills.size());
        for (ResolvedSkillBinding skill : skills) {
            CanonicalHashing.appendFramed(canonical, "skillId", skill.getId());
            CanonicalHashing.appendFramed(
                    canonical, "skillVersion", skill.getVersion());
            CanonicalHashing.appendFramed(canonical, "skillSource", skill.getSource());
            CanonicalHashing.appendFramed(
                    canonical, "skillPackageHash", skill.getPackageHash());
            CanonicalHashing.appendFramed(
                    canonical, "skillTrustTier", skill.getTrustTier());
        }
        CanonicalHashing.appendFramed(canonical, "mcpCount", mcpServers.size());
        for (ResolvedMcpServerBinding mcp : mcpServers) {
            CanonicalHashing.appendFramed(canonical, "mcpId", mcp.getId());
            CanonicalHashing.appendFramed(canonical, "mcpVersion", mcp.getVersion());
            CanonicalHashing.appendFramed(
                    canonical, "mcpDefinitionHash", mcp.getDefinitionHash());
            CanonicalHashing.appendFramed(canonical, "mcpAccess", mcp.getAccess());
            CanonicalHashing.appendFramed(canonical, "mcpTransport", mcp.getTransport());
        }
        CanonicalHashing.appendFramed(canonical, "rejectedCount", rejected.size());
        for (RejectedCapability rejection : rejected) {
            CanonicalHashing.appendFramed(
                    canonical, "rejectedId", rejection.getId());
            CanonicalHashing.appendFramed(
                    canonical, "rejectedReason", rejection.getReasonCode());
        }
        return CanonicalHashing.sha256(canonical.toString());
    }
}
