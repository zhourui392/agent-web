package com.example.agentweb.app.workbench.handoff;

import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Owner 侧 Handoff 查询、upsert 与 Reception 门面。
 *
 * <p>写规则委托 PhaseHandoffAppService 和领域对象；本类只负责 Owner-safe
 * 查询投影、HTTP upsert 编排和稳定冲突翻译。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
@Transactional(readOnly = true)
public class PhaseHandoffOwnerService {

    private final PhaseHandoffAppService mutationService;
    private final WorkbenchRepository workbenchRepository;
    private final PhaseHandoffRepository handoffRepository;
    private final PhaseHandoffRevisionRepository revisionRepository;
    private final HandoffReceptionRepository receptionRepository;
    private final WorkbenchTelemetry telemetry;

    public PhaseHandoffOwnerService(
            PhaseHandoffAppService mutationService,
            WorkbenchRepository workbenchRepository,
            PhaseHandoffRepository handoffRepository,
            PhaseHandoffRevisionRepository revisionRepository,
            HandoffReceptionRepository receptionRepository,
            WorkbenchTelemetry telemetry) {
        this.mutationService = Objects.requireNonNull(
                mutationService, "mutationService");
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.handoffRepository = Objects.requireNonNull(
                handoffRepository, "handoffRepository");
        this.revisionRepository = Objects.requireNonNull(
                revisionRepository, "revisionRepository");
        this.receptionRepository = Objects.requireNonNull(
                receptionRepository, "receptionRepository");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public PhaseHandoffProjection get(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase phase) {
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        PhaseHandoff handoff = requireHandoff(workbenchId, phase);
        return PhaseHandoffProjection.from(
                handoff, isReadOnly(workbench, actor));
    }

    @Transactional
    public PhaseHandoffProjection save(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase phase, long expectedVersion,
            PhaseHandoffContentCommand content) {
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException(
                    "handoff expected version must not be negative");
        }
        Objects.requireNonNull(content, "content");
        requireOperableWorkbench(actor, workbenchId);
        Optional<PhaseHandoff> existing =
                handoffRepository.find(workbenchId, phase);
        if (!existing.isPresent() && expectedVersion != 0L) {
            throw handoffNotFound();
        }
        try {
            PhaseHandoff saved;
            if (existing.isPresent()) {
                saved = mutationService.revise(
                        actor, new RevisePhaseHandoffCommand(
                                workbenchId, phase, expectedVersion, content));
            } else {
                saved = mutationService.create(
                        actor, new CreatePhaseHandoffCommand(
                                workbenchId, phase, content));
            }
            return PhaseHandoffProjection.from(saved, false);
        } catch (WorkbenchDomainException failure) {
            if (failure.getCode() != WorkbenchErrorCode.VERSION_CONFLICT) {
                throw failure;
            }
            recordConflict();
            PhaseHandoffProjection current = handoffRepository
                    .find(workbenchId, phase)
                    .map(value -> PhaseHandoffProjection.from(value, false))
                    .orElse(null);
            throw new HandoffApplicationException(
                    HandoffApplicationErrorCode.VERSION_CONFLICT,
                    "workbench handoff version conflict", current);
        }
    }

    public HandoffSourcePreview source(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase targetPhase) {
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        boolean readOnly = isReadOnly(workbench, actor);
        Optional<WorkbenchPhase> sourcePhase =
                targetPhase.defaultHandoffSource();
        if (!sourcePhase.isPresent()) {
            return HandoffSourcePreview.withoutDefaultSource(targetPhase);
        }
        WorkbenchPhase upstream = sourcePhase.get();
        Optional<PhaseHandoff> latest =
                handoffRepository.find(workbenchId, upstream);
        Optional<HandoffReception> reception = receptionRepository.find(
                workbenchId, targetPhase, upstream);
        if (reception.isPresent() && !latest.isPresent()) {
            throw handoffNotFound();
        }
        PhaseHandoffProjection latestView = latest
                .map(value -> PhaseHandoffProjection.from(value, readOnly))
                .orElse(null);
        if (!reception.isPresent()) {
            return new HandoffSourcePreview(
                    targetPhase, latestView, null, null, false, null);
        }
        HandoffReception acceptedReception = reception.get();
        PhaseHandoffRevision acceptedRevision = revisionRepository.findExact(
                        workbenchId, upstream,
                        acceptedReception.getSourceVersion(),
                        acceptedReception.getSourceHash())
                .orElseThrow(PhaseHandoffOwnerService::handoffNotFound);
        PhaseHandoffProjection acceptedView =
                PhaseHandoffProjection.from(acceptedRevision, readOnly);
        boolean stale = acceptedReception.isStale(
                latest.get().getVersion(), latest.get().getContentHash());
        HandoffDiffSummary diff = stale
                ? HandoffDiffSummary.between(acceptedView, latestView) : null;
        return new HandoffSourcePreview(
                targetPhase, latestView,
                HandoffReceptionProjection.from(acceptedReception),
                acceptedView, stale, diff);
    }

    @Transactional
    public HandoffReceptionProjection accept(
            OwnerReference actor, AcceptHandoffReceptionCommand command) {
        try {
            return HandoffReceptionProjection.from(
                    mutationService.accept(actor, command));
        } catch (WorkbenchDomainException failure) {
            if (failure.getCode() != WorkbenchErrorCode.VERSION_CONFLICT) {
                throw failure;
            }
            recordConflict();
            throw new HandoffApplicationException(
                    HandoffApplicationErrorCode.SOURCE_CHANGED,
                    "workbench handoff source changed");
        }
    }

    private Workbench requireOwnedWorkbench(
            OwnerReference actor, WorkbenchId workbenchId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workbenchId, "workbenchId");
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(PhaseHandoffOwnerService::workbenchNotFound);
        try {
            workbench.requireOwnedBy(actor);
            return workbench;
        } catch (WorkbenchDomainException failure) {
            if (failure.getCode() != WorkbenchErrorCode.OWNER_REQUIRED) {
                throw failure;
            }
            throw workbenchNotFound();
        }
    }

    private Workbench requireOperableWorkbench(
            OwnerReference actor, WorkbenchId workbenchId) {
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        workbench.requireOperableBy(actor);
        return workbench;
    }

    private boolean isReadOnly(
            Workbench workbench, OwnerReference actor) {
        try {
            workbench.requireOperableBy(actor);
            return false;
        } catch (WorkbenchDomainException failure) {
            if (failure.getCode() == WorkbenchErrorCode.ARCHIVED) {
                return true;
            }
            throw failure;
        }
    }

    private PhaseHandoff requireHandoff(
            WorkbenchId workbenchId, WorkbenchPhase phase) {
        return handoffRepository.find(workbenchId, phase)
                .orElseThrow(PhaseHandoffOwnerService::handoffNotFound);
    }

    private void recordConflict() {
        telemetry.handoffConflict();
        telemetry.writeConflict();
    }

    private static HandoffApplicationException workbenchNotFound() {
        return new HandoffApplicationException(
                HandoffApplicationErrorCode.WORKBENCH_NOT_FOUND,
                "workbench was not found");
    }

    private static HandoffApplicationException handoffNotFound() {
        return new HandoffApplicationException(
                HandoffApplicationErrorCode.HANDOFF_NOT_FOUND,
                "phase handoff was not found");
    }
}
