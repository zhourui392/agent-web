package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prompt Pack、固定装配顺序和 Capability Snapshot 领域测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class HarnessPromptAssemblerTest {

    private final HarnessPromptAssembler assembler = new HarnessPromptAssembler();

    @Test
    void shouldAssemblePromptInFixedPrecedenceOrder() {
        PromptPack pack = promptPack(HarnessStage.ANALYSIS);
        SkillSelection selection = selection();

        HarnessPromptAssembly assembly = assembler.assemble(new HarnessPromptAssemblyRequest(
                "platform safety", "environment guardrail", "stage contract", pack,
                selection, "approved upstream", "current input"));

        assertEquals(Arrays.asList(
                        PromptPartType.PLATFORM_SAFETY,
                        PromptPartType.ENVIRONMENT_GUARDRAIL,
                        PromptPartType.STAGE_CONTRACT,
                        PromptPartType.STAGE_SYSTEM,
                        PromptPartType.STAGE_TASK,
                        PromptPartType.STAGE_GATE_HINTS,
                        PromptPartType.SELECTED_SKILLS,
                        PromptPartType.UPSTREAM_ARTIFACTS,
                        PromptPartType.CURRENT_INPUT,
                        PromptPartType.OUTPUT_CONTRACT),
                assembly.partTypes());
        assertEquals(64, assembly.getPromptHash().length());
        assertFalse(assembly.getFinalPrompt().contains("AGENTS.md"));
        assertFalse(assembly.getFinalPrompt().contains("CLAUDE.md"));
    }

    @Test
    void shouldProduceStablePromptAndSnapshotHashesForSameInputsAndResources() {
        PromptPack pack = promptPack(HarnessStage.ANALYSIS);
        SkillSelection selection = selection();
        HarnessPromptAssemblyRequest request = new HarnessPromptAssemblyRequest(
                "platform safety", "environment guardrail", "stage contract", pack,
                selection, "approved upstream", "current input");

        HarnessPromptAssembly firstPrompt = assembler.assemble(request);
        HarnessPromptAssembly secondPrompt = assembler.assemble(request);
        CapabilitySnapshot first = CapabilitySnapshot.create(
                "run-1", HarnessStage.ANALYSIS, 1, AgentRuntime.CODEX, "test", "policy-1",
                pack, selection, firstPrompt, Instant.parse("2026-07-23T10:00:00Z"));
        CapabilitySnapshot second = CapabilitySnapshot.create(
                "run-2", HarnessStage.ANALYSIS, 2, AgentRuntime.CODEX, "test", "policy-1",
                pack, selection, secondPrompt, Instant.parse("2026-07-23T11:00:00Z"));

        assertEquals(firstPrompt.getPromptHash(), secondPrompt.getPromptHash());
        assertEquals(first.getSnapshotHash(), second.getSnapshotHash());
        assertEquals(first.getSnapshotHash(), CapabilitySnapshot.restore(
                first.getRunId(), first.getStage(), first.getAttemptNumber(), first.getRuntime(),
                first.getEnvironment(), first.getPolicyVersion(), first.getPromptPackId(),
                first.getPromptPackVersion(), first.getPromptPackHash(), first.getPromptResourceHashes(),
                first.getSelectedSkills(), first.getRejectedSkills(), first.getCapabilityDecisions(),
                first.getPromptParts(), first.getFinalPrompt(), first.getPromptHash(),
                first.getSnapshotHash(), first.getCreatedAt()).getSnapshotHash());
    }

    @Test
    void shouldRejectPromptPackMissingRequiredResource() {
        PromptPackManifest manifest = manifest(HarnessStage.ANALYSIS);
        List<PromptPackResource> incomplete = Arrays.asList(
                resource(PromptResourceRole.SYSTEM, "system.md", "system"),
                resource(PromptResourceRole.TASK, "task.md", "task"),
                resource(PromptResourceRole.OUTPUT_CONTRACT, "output-contract.md", "output"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new PromptPack(manifest, incomplete, hash('a')));

        assertEquals("prompt pack must contain exactly all required resources", error.getMessage());
    }

    @Test
    void shouldGiveDifferentStagesDifferentDefaultSkills() {
        assertEquals(Collections.singleton("domain-modeling-audit"),
                StageCapabilityPolicy.defaultsFor(HarnessStage.ANALYSIS));
        assertEquals(Collections.singleton("java-tdd"),
                StageCapabilityPolicy.defaultsFor(HarnessStage.IMPLEMENTATION));
    }

    private PromptPack promptPack(HarnessStage stage) {
        return new PromptPack(manifest(stage), Arrays.asList(
                resource(PromptResourceRole.SYSTEM, "system.md", "system"),
                resource(PromptResourceRole.TASK, "task.md", "task"),
                resource(PromptResourceRole.OUTPUT_CONTRACT, "output-contract.md", "output"),
                resource(PromptResourceRole.GATE_HINTS, "gate-hints.md", "gates")), hash('a'));
    }

    private PromptPackManifest manifest(HarnessStage stage) {
        Map<PromptResourceRole, String> paths = new EnumMap<PromptResourceRole, String>(PromptResourceRole.class);
        paths.put(PromptResourceRole.SYSTEM, "system.md");
        paths.put(PromptResourceRole.TASK, "task.md");
        paths.put(PromptResourceRole.OUTPUT_CONTRACT, "output-contract.md");
        paths.put(PromptResourceRole.GATE_HINTS, "gate-hints.md");
        return new PromptPackManifest(stage.name().toLowerCase(), "1.0.0", stage, paths);
    }

    private PromptPackResource resource(PromptResourceRole role, String path, String content) {
        return new PromptPackResource(role, path, content, HarnessHashing.sha256(content));
    }

    private SkillSelection selection() {
        SkillManifest manifest = new SkillManifest("analysis-core", "1.0.0", "analysis",
                Collections.singleton(HarnessStage.ANALYSIS), Collections.singleton("java"),
                Collections.<String>emptySet(), "SKILL.md", Collections.<String>emptySet(),
                Collections.<SkillDependency>emptyList(), Collections.<String>emptySet(),
                Collections.singleton(AgentRuntime.CODEX), SkillTrustSource.PLATFORM,
                Collections.singletonList(CapabilityRequest.command("mvn-test")));
        Map<String, String> resourceHashes = new LinkedHashMap<String, String>();
        resourceHashes.put("SKILL.md", hash('b'));
        SkillPackage skillPackage = new SkillPackage(manifest, hash('c'), "# Analysis skill", resourceHashes);
        SelectedSkill selected = new SelectedSkill(skillPackage, SkillSelectionReason.STAGE_DEFAULT);
        CapabilityDecision denied = new CapabilityDecision("analysis-core",
                CapabilityRequest.command("mvn-test"), false, "NOT_GRANTED");
        return new SkillSelection(Collections.singletonList(selected),
                Collections.<RejectedSkill>emptyList(), Collections.singletonList(denied));
    }

    private String hash(char value) {
        char[] chars = new char[64];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
