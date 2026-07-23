package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.CapabilityGrant;
import com.example.agentweb.domain.harness.CapabilitySelectionRequest;
import com.example.agentweb.domain.harness.CapabilitySnapshot;
import com.example.agentweb.domain.harness.CapabilitySnapshotRepository;
import com.example.agentweb.domain.harness.HarnessPromptAssembly;
import com.example.agentweb.domain.harness.HarnessPromptAssemblyRequest;
import com.example.agentweb.domain.harness.HarnessPromptAssembler;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.PromptPack;
import com.example.agentweb.domain.harness.PromptPackCatalog;
import com.example.agentweb.domain.harness.PromptPackManifest;
import com.example.agentweb.domain.harness.PromptPackResource;
import com.example.agentweb.domain.harness.PromptResourceRole;
import com.example.agentweb.domain.harness.SkillCatalog;
import com.example.agentweb.domain.harness.SkillDependency;
import com.example.agentweb.domain.harness.SkillManifest;
import com.example.agentweb.domain.harness.SkillPackage;
import com.example.agentweb.domain.harness.SkillSelectionPolicy;
import com.example.agentweb.domain.harness.SkillSelection;
import com.example.agentweb.domain.harness.SkillTrustSource;
import com.example.agentweb.domain.harness.StageCapabilityPolicy;
import com.example.agentweb.domain.harness.StageContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Capability Resolver 应用编排测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@ExtendWith(MockitoExtension.class)
class HarnessCapabilityServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:02:00Z");

    @Mock
    private HarnessRunRepository runRepository;
    @Mock
    private CapabilitySnapshotRepository snapshotRepository;
    @Mock
    private PromptPackCatalog promptPackCatalog;
    @Mock
    private SkillCatalog skillCatalog;

    private HarnessCapabilityServiceImpl service;
    private HarnessRun run;

    @BeforeEach
    void setUp() {
        HarnessCapabilitySettings settings = new HarnessCapabilitySettings(
                "harness-capability-policy@1.0.0", "platform safety", "test guardrail");
        service = new HarnessCapabilityServiceImpl(runRepository, snapshotRepository,
                promptPackCatalog, skillCatalog, new SkillSelectionPolicy(),
                new HarnessPromptAssembler(), settings, Clock.fixed(NOW, ZoneOffset.UTC));
        run = HarnessRun.create("run-1", "title", "/workspace", "CODEX", "test",
                "harness@1.0.0", "admin", "create-1", StageContract.mvpDefaults(),
                Instant.parse("2026-07-23T10:00:00Z"));
        run.startStage(HarnessStage.ANALYSIS, "start-1", Instant.parse("2026-07-23T10:01:00Z"));
    }

    @Test
    void shouldLoadCatalogApplyDomainPoliciesAndPersistSnapshotBeforeReturning() {
        PromptPack promptPack = promptPack();
        SkillPackage skillPackage = skillPackage();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(snapshotRepository.find("run-1", HarnessStage.ANALYSIS, 1)).thenReturn(Optional.empty());
        when(promptPackCatalog.resolve(HarnessStage.ANALYSIS)).thenReturn(promptPack);
        when(skillCatalog.discover()).thenReturn(Collections.singletonList(skillPackage));
        when(snapshotRepository.saveIfAbsent(any(CapabilitySnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CapabilitySnapshotView view = service.resolve(command());

        assertEquals("run-1", view.getRunId());
        assertEquals(1, view.getAttemptNumber());
        assertEquals("analysis", view.getPromptPackId());
        assertEquals("domain-modeling-audit", view.getSelectedSkills().get(0).getId());
        assertEquals(64, view.getSnapshotHash().length());
        InOrder order = inOrder(runRepository, snapshotRepository, promptPackCatalog, skillCatalog);
        order.verify(runRepository).findById("run-1");
        order.verify(snapshotRepository).find("run-1", HarnessStage.ANALYSIS, 1);
        order.verify(promptPackCatalog).resolve(HarnessStage.ANALYSIS);
        order.verify(skillCatalog).discover();
        order.verify(snapshotRepository).saveIfAbsent(any(CapabilitySnapshot.class));
    }

    @Test
    void shouldReturnExistingAttemptSnapshotWithoutReReadingChangedCatalog() {
        CapabilitySnapshot existing = resolvedSnapshot();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(snapshotRepository.find("run-1", HarnessStage.ANALYSIS, 1))
                .thenReturn(Optional.of(existing));

        CapabilitySnapshotView view = service.resolve(command());

        assertEquals(existing.getSnapshotHash(), view.getSnapshotHash());
        verify(promptPackCatalog, never()).resolve(any(HarnessStage.class));
        verify(skillCatalog, never()).discover();
        verify(snapshotRepository, never()).saveIfAbsent(any(CapabilitySnapshot.class));
    }

    private ResolveHarnessCapabilityCommand command() {
        return new ResolveHarnessCapabilityCommand("run-1", HarnessStage.ANALYSIS,
                Collections.<String>emptySet(), Collections.singleton("java"),
                Collections.<String>emptySet(), CapabilityGrant.none(),
                "approved upstream", "current input");
    }

    private CapabilitySnapshot resolvedSnapshot() {
        PromptPack pack = promptPack();
        SkillSelection selection = new SkillSelectionPolicy().select(
                new CapabilitySelectionRequest(HarnessStage.ANALYSIS, AgentRuntime.CODEX,
                        StageCapabilityPolicy.defaultsFor(HarnessStage.ANALYSIS),
                        Collections.<String>emptySet(), Collections.singleton("java"),
                        Collections.<String>emptySet(), CapabilityGrant.none()),
                Collections.singletonList(skillPackage()));
        HarnessPromptAssembly assembly = new HarnessPromptAssembler().assemble(
                new HarnessPromptAssemblyRequest("platform safety", "test guardrail",
                        run.capabilityStageContract(HarnessStage.ANALYSIS).promptSummary(), pack,
                        selection, "approved upstream", "current input"));
        return CapabilitySnapshot.create("run-1", HarnessStage.ANALYSIS, 1, AgentRuntime.CODEX,
                "test", "harness-capability-policy@1.0.0", pack, selection, assembly, NOW);
    }

    private PromptPack promptPack() {
        Map<PromptResourceRole, String> paths = new EnumMap<PromptResourceRole, String>(PromptResourceRole.class);
        paths.put(PromptResourceRole.SYSTEM, "system.md");
        paths.put(PromptResourceRole.TASK, "task.md");
        paths.put(PromptResourceRole.OUTPUT_CONTRACT, "output-contract.md");
        paths.put(PromptResourceRole.GATE_HINTS, "gate-hints.md");
        PromptPackManifest manifest = new PromptPackManifest(
                "analysis", "1.0.0", HarnessStage.ANALYSIS, paths);
        return new PromptPack(manifest, Arrays.asList(
                resource(PromptResourceRole.SYSTEM, "system.md", "system"),
                resource(PromptResourceRole.TASK, "task.md", "task"),
                resource(PromptResourceRole.OUTPUT_CONTRACT, "output-contract.md", "output"),
                resource(PromptResourceRole.GATE_HINTS, "gate-hints.md", "gates")), hash('a'));
    }

    private PromptPackResource resource(PromptResourceRole role, String path, String content) {
        return new PromptPackResource(role, path, content,
                com.example.agentweb.domain.harness.HarnessHashing.sha256(content));
    }

    private SkillPackage skillPackage() {
        SkillManifest manifest = new SkillManifest("domain-modeling-audit", "1.0.0", "domain audit",
                Collections.singleton(HarnessStage.ANALYSIS), Collections.singleton("ddd"),
                Collections.<String>emptySet(), "SKILL.md", Collections.<String>emptySet(),
                Collections.<SkillDependency>emptyList(), Collections.<String>emptySet(),
                Collections.singleton(AgentRuntime.CODEX), SkillTrustSource.PLATFORM,
                Collections.emptyList());
        return new SkillPackage(manifest, hash('b'), "# Domain modeling audit",
                Collections.singletonMap("SKILL.md", hash('c')));
    }

    private String hash(char value) {
        char[] chars = new char[64];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
