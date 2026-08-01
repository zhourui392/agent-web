package com.example.agentweb.app.workbench.handoff;

import com.example.agentweb.domain.workbench.HandoffReception;
import com.example.agentweb.domain.workbench.HandoffReceptionRepository;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseHandoff;
import com.example.agentweb.domain.workbench.PhaseHandoffRepository;
import com.example.agentweb.domain.workbench.PhaseHandoffRevision;
import com.example.agentweb.domain.workbench.PhaseHandoffRevisionRepository;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Phase Handoff 创建、修订与接收的事务编排。
 *
 * <p>Owner、归档、版本、Hash、Pinned File、Referenced Run 和 Reception 语义
 * 均委托 Workbench、PhaseHandoff 与 HandoffReception 领域对象。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Transactional
@Service
public class PhaseHandoffAppService {

    private final WorkbenchRepository workbenchRepository;
    private final PhaseHandoffRepository handoffRepository;
    private final PhaseHandoffRevisionRepository revisionRepository;
    private final HandoffReceptionRepository receptionRepository;
    private final HandoffRunReferenceResolver runReferenceResolver;
    private final Clock clock;

    public PhaseHandoffAppService(
            WorkbenchRepository workbenchRepository,
            PhaseHandoffRepository handoffRepository,
            PhaseHandoffRevisionRepository revisionRepository,
            HandoffReceptionRepository receptionRepository,
            HandoffRunReferenceResolver runReferenceResolver,
            Clock clock) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.handoffRepository = Objects.requireNonNull(
                handoffRepository, "handoffRepository");
        this.revisionRepository = Objects.requireNonNull(
                revisionRepository, "revisionRepository");
        this.receptionRepository = Objects.requireNonNull(
                receptionRepository, "receptionRepository");
        this.runReferenceResolver = Objects.requireNonNull(
                runReferenceResolver, "runReferenceResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PhaseHandoff create(
            OwnerReference actor, CreatePhaseHandoffCommand command) {
        Objects.requireNonNull(command, "command");
        Workbench workbench = requireOperableWorkbench(
                actor, command.getWorkbenchId());
        PhaseHandoffContentCommand content = command.getContent();
        List<WorkbenchRunReference> referencedRuns =
                runReferenceResolver.requireReferences(
                        content.getReferencedRunIds());
        PhaseHandoff handoff = PhaseHandoff.create(
                command.getWorkbenchId(), command.getSourcePhase(),
                content.getSummary(), content.getDecisions(),
                content.getOpenQuestions(), content.getPinnedFiles(),
                referencedRuns, workbench.getRepositoryScope(), actor,
                clock.instant());
        handoffRepository.add(handoff);
        revisionRepository.append(PhaseHandoffRevision.capture(handoff));
        return handoff;
    }

    public PhaseHandoff revise(
            OwnerReference actor, RevisePhaseHandoffCommand command) {
        Objects.requireNonNull(command, "command");
        Workbench workbench = requireOperableWorkbench(
                actor, command.getWorkbenchId());
        PhaseHandoff handoff = requireHandoff(
                command.getWorkbenchId(), command.getSourcePhase());
        PhaseHandoffContentCommand content = command.getContent();
        List<WorkbenchRunReference> referencedRuns =
                runReferenceResolver.requireReferences(
                        content.getReferencedRunIds());
        handoff.update(
                command.getExpectedVersion(), content.getSummary(),
                content.getDecisions(), content.getOpenQuestions(),
                content.getPinnedFiles(), referencedRuns,
                workbench.getRepositoryScope(), actor, clock.instant());
        handoffRepository.update(handoff);
        revisionRepository.append(PhaseHandoffRevision.capture(handoff));
        return handoff;
    }

    public HandoffReception accept(
            OwnerReference actor, AcceptHandoffReceptionCommand command) {
        Objects.requireNonNull(command, "command");
        requireOperableWorkbench(actor, command.getWorkbenchId());
        PhaseHandoff source = requireHandoff(
                command.getWorkbenchId(), command.getSourcePhase());
        HandoffReception reception = HandoffReception.accept(
                command.getWorkbenchId(), command.getTargetPhase(),
                command.getSourcePhase(), command.getSourceVersion(),
                command.getSourceHash(), actor, clock.instant());
        reception.requireLatest(source.getVersion(), source.getContentHash());
        receptionRepository.save(reception);
        return reception;
    }

    @Transactional(readOnly = true)
    public PhaseHandoffRevision requireAcceptedRevision(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase targetPhase, WorkbenchPhase sourcePhase) {
        requireOperableWorkbench(actor, workbenchId);
        HandoffReception reception = receptionRepository.find(
                        workbenchId, targetPhase, sourcePhase)
                .orElseThrow(() -> new HandoffApplicationException(
                        HandoffApplicationErrorCode.HANDOFF_NOT_FOUND,
                        "accepted handoff reception was not found"));
        return revisionRepository.findExact(
                        workbenchId, sourcePhase, reception.getSourceVersion(),
                        reception.getSourceHash())
                .orElseThrow(() -> new HandoffApplicationException(
                        HandoffApplicationErrorCode.HANDOFF_NOT_FOUND,
                        "accepted handoff revision was not found"));
    }

    private Workbench requireOperableWorkbench(
            OwnerReference actor, WorkbenchId workbenchId) {
        Objects.requireNonNull(actor, "actor");
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(() -> new HandoffApplicationException(
                        HandoffApplicationErrorCode.WORKBENCH_NOT_FOUND,
                        "workbench was not found"));
        try {
            workbench.requireOperableBy(actor);
            return workbench;
        } catch (WorkbenchDomainException ex) {
            if (ex.getCode() != WorkbenchErrorCode.OWNER_REQUIRED) {
                throw ex;
            }
            throw new HandoffApplicationException(
                    HandoffApplicationErrorCode.WORKBENCH_NOT_FOUND,
                    "workbench was not found");
        }
    }

    private PhaseHandoff requireHandoff(
            WorkbenchId workbenchId, WorkbenchPhase sourcePhase) {
        return handoffRepository.find(workbenchId, sourcePhase)
                .orElseThrow(() -> new HandoffApplicationException(
                        HandoffApplicationErrorCode.HANDOFF_NOT_FOUND,
                        "phase handoff was not found"));
    }
}
