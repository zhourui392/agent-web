package com.example.agentweb.domain.capability;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author alex
 * @since 2026-08-01
 */
class ResolvedCapabilityBindingTest {

    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void shouldKeepImmutableResolvedFacts() {
        ResolvedRuleBinding rule = new ResolvedRuleBinding(
                "platform/safety", "1", "PLATFORM", HASH, true, "platform safety");
        ResolvedSkillBinding skill = new ResolvedSkillBinding(
                "java-tdd", "1", "PLATFORM", HASH, "PLATFORM");
        ResolvedMcpServerBinding mcp = new ResolvedMcpServerBinding(
                "repository-query", "1", HASH, CapabilityAccess.READ, "STDIO");
        RejectedCapability rejected = new RejectedCapability("optional-skill", "NOT_AVAILABLE");

        ResolvedCapabilityBinding binding = ResolvedCapabilityBinding.resolve(
                "workbench-policy@1", "workbench-analysis", "1", HASH,
                Collections.singletonList(rule), Collections.singletonList(skill),
                Collections.singletonList(mcp), Collections.singletonList(rejected),
                "CODEX");

        assertEquals("workbench-policy@1", binding.getPolicyVersion());
        assertEquals(rule, binding.getRules().get(0));
        assertEquals(skill, binding.getSkills().get(0));
        assertEquals(mcp, binding.getMcpServers().get(0));
        assertEquals(rejected, binding.getRejected().get(0));
        assertEquals(64, binding.getBindingHash().length());
        assertThrows(UnsupportedOperationException.class,
                () -> binding.getRules().add(rule));
    }

    @Test
    void shouldRejectMissingProfileHashOrNullEntries() {
        assertThrows(IllegalArgumentException.class, () -> ResolvedCapabilityBinding.resolve(
                "policy", "profile", "1", "bad-hash",
                Collections.<ResolvedRuleBinding>emptyList(),
                Collections.<ResolvedSkillBinding>emptyList(),
                Collections.<ResolvedMcpServerBinding>emptyList(),
                Collections.<RejectedCapability>emptyList(), "CODEX"));

        assertThrows(IllegalArgumentException.class, () -> ResolvedCapabilityBinding.resolve(
                "policy", "profile", "1", HASH,
                Collections.singletonList((ResolvedRuleBinding) null),
                Collections.<ResolvedSkillBinding>emptyList(),
                Collections.<ResolvedMcpServerBinding>emptyList(),
                Collections.<RejectedCapability>emptyList(), "CODEX"));
    }

    @Test
    void bindingHashShouldBeCanonicalAndInputOrderIndependent() {
        ResolvedRuleBinding ruleA = new ResolvedRuleBinding(
                "platform/a", "1", "PLATFORM", repeat('a'), true, "rule a");
        ResolvedRuleBinding ruleB = new ResolvedRuleBinding(
                "workspace/b", "2", "WORKSPACE", repeat('b'), false, "rule b");
        ResolvedSkillBinding skillA = new ResolvedSkillBinding(
                "java-tdd", "1", "PLATFORM", repeat('c'), "PLATFORM");
        ResolvedSkillBinding skillB = new ResolvedSkillBinding(
                "code-search", "3", "PLATFORM", repeat('d'), "PLATFORM");

        ResolvedCapabilityBinding first = ResolvedCapabilityBinding.resolve(
                "policy@1", "profile", "7", repeat('e'),
                Arrays.asList(ruleB, ruleA), Arrays.asList(skillA, skillB),
                Collections.<ResolvedMcpServerBinding>emptyList(),
                Collections.<RejectedCapability>emptyList(), "CODEX");
        ResolvedCapabilityBinding second = ResolvedCapabilityBinding.resolve(
                "policy@1", "profile", "7", repeat('e'),
                Arrays.asList(ruleA, ruleB), Arrays.asList(skillB, skillA),
                Collections.<ResolvedMcpServerBinding>emptyList(),
                Collections.<RejectedCapability>emptyList(), "CODEX");

        assertEquals(first.getBindingHash(), second.getBindingHash());
        assertEquals("platform/a", first.getRules().get(0).getId());
        assertEquals("code-search", first.getSkills().get(0).getId());
    }

    @Test
    void restoreShouldVerifyPersistedHashAndRejectDuplicateCapabilityIds() {
        ResolvedCapabilityBinding resolved = ResolvedCapabilityBinding.resolve(
                "policy@1", "profile", "1", repeat('a'),
                Collections.<ResolvedRuleBinding>emptyList(),
                Collections.<ResolvedSkillBinding>emptyList(),
                Collections.<ResolvedMcpServerBinding>emptyList(),
                Collections.<RejectedCapability>emptyList(), "CODEX");

        ResolvedCapabilityBinding restored = ResolvedCapabilityBinding.restore(
                resolved.getPolicyVersion(), resolved.getProfileId(),
                resolved.getProfileVersion(), resolved.getProfileHash(),
                resolved.getRules(), resolved.getSkills(), resolved.getMcpServers(),
                resolved.getRejected(), resolved.getRuntimeCompatibility(),
                resolved.getBindingHash());

        assertEquals(resolved.getBindingHash(), restored.getBindingHash());
        assertThrows(IllegalArgumentException.class,
                () -> ResolvedCapabilityBinding.restore(
                        resolved.getPolicyVersion(), resolved.getProfileId(),
                        resolved.getProfileVersion(), resolved.getProfileHash(),
                        resolved.getRules(), resolved.getSkills(),
                        resolved.getMcpServers(), resolved.getRejected(),
                        resolved.getRuntimeCompatibility(), repeat('f')));

        ResolvedRuleBinding duplicateOne = new ResolvedRuleBinding(
                "platform/duplicate", "1", "PLATFORM", repeat('1'), true, "one");
        ResolvedRuleBinding duplicateTwo = new ResolvedRuleBinding(
                "platform/duplicate", "2", "PLATFORM", repeat('2'), true, "two");
        assertThrows(IllegalArgumentException.class,
                () -> ResolvedCapabilityBinding.resolve(
                        "policy@1", "profile", "1", repeat('a'),
                        Arrays.asList(duplicateOne, duplicateTwo),
                        Collections.<ResolvedSkillBinding>emptyList(),
                        Collections.<ResolvedMcpServerBinding>emptyList(),
                        Collections.<RejectedCapability>emptyList(), "CODEX"));
    }

    private static String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
