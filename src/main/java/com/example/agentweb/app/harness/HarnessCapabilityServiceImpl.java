package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.CapabilitySelectionRequest;
import com.example.agentweb.domain.harness.CapabilitySnapshot;
import com.example.agentweb.domain.harness.CapabilitySnapshotRepository;
import com.example.agentweb.domain.harness.HarnessPromptAssembler;
import com.example.agentweb.domain.harness.HarnessPromptAssembly;
import com.example.agentweb.domain.harness.HarnessPromptAssemblyRequest;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.PromptPack;
import com.example.agentweb.domain.harness.PromptPackCatalog;
import com.example.agentweb.domain.harness.SkillCatalog;
import com.example.agentweb.domain.harness.SkillSelection;
import com.example.agentweb.domain.harness.SkillSelectionPolicy;
import com.example.agentweb.domain.harness.StageCapabilityPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * Capability Resolver 应用编排：加载、领域决策、装配并先固化 Snapshot。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Service
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessCapabilityServiceImpl implements HarnessCapabilityService {

    private final HarnessRunRepository runRepository;
    private final CapabilitySnapshotRepository snapshotRepository;
    private final PromptPackCatalog promptPackCatalog;
    private final SkillCatalog skillCatalog;
    private final SkillSelectionPolicy selectionPolicy;
    private final HarnessPromptAssembler promptAssembler;
    private final HarnessCapabilitySettings settings;
    private final Clock clock;

    public HarnessCapabilityServiceImpl(HarnessRunRepository runRepository,
                                        CapabilitySnapshotRepository snapshotRepository,
                                        PromptPackCatalog promptPackCatalog,
                                        SkillCatalog skillCatalog,
                                        SkillSelectionPolicy selectionPolicy,
                                        HarnessPromptAssembler promptAssembler,
                                        HarnessCapabilitySettings settings, Clock clock) {
        this.runRepository = runRepository;
        this.snapshotRepository = snapshotRepository;
        this.promptPackCatalog = promptPackCatalog;
        this.skillCatalog = skillCatalog;
        this.selectionPolicy = selectionPolicy;
        this.promptAssembler = promptAssembler;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CapabilitySnapshotView resolve(ResolveHarnessCapabilityCommand command) {
        HarnessRun run = runRepository.findById(command.getRunId())
                .orElseThrow(() -> new HarnessRunNotFoundException(command.getRunId()));
        int attemptNumber = run.capabilitySnapshotAttempt(command.getStage());
        Optional<CapabilitySnapshot> existing = snapshotRepository.find(
                run.getId(), command.getStage(), attemptNumber);
        if (existing.isPresent()) {
            return new CapabilitySnapshotView(existing.get());
        }

        PromptPack promptPack = promptPackCatalog.resolve(command.getStage());
        CapabilitySelectionRequest selectionRequest = new CapabilitySelectionRequest(
                command.getStage(), run.capabilityRuntime(),
                StageCapabilityPolicy.defaultsFor(command.getStage()), command.getExplicitSkillIds(),
                command.getTechnicalTags(), command.getApprovedWorkspaceSkillIds(),
                command.getCapabilityGrant());
        SkillSelection selection = selectionPolicy.select(selectionRequest, skillCatalog.discover());
        HarnessPromptAssembly assembly = promptAssembler.assemble(new HarnessPromptAssemblyRequest(
                settings.getPlatformSafety(), settings.getEnvironmentGuardrail(),
                run.capabilityStageContract(command.getStage()).promptSummary(), promptPack,
                selection, command.getUpstreamArtifacts(), command.getCurrentInput()));
        CapabilitySnapshot snapshot = CapabilitySnapshot.create(run.getId(), command.getStage(),
                attemptNumber, run.capabilityRuntime(), run.capabilityEnvironment(),
                settings.getPolicyVersion(), promptPack, selection, assembly, clock.instant());
        return new CapabilitySnapshotView(snapshotRepository.saveIfAbsent(snapshot));
    }
}
