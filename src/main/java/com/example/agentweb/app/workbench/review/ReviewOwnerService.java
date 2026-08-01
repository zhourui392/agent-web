package com.example.agentweb.app.workbench.review;

import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmation;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmationRepository;
import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.domain.workbench.ReviewOpinionRepository;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Review Opinion 与显式 MODIFY Confirmation 的 Owner 应用编排。
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
@Transactional(readOnly = true)
public class ReviewOwnerService {

    private final WorkbenchRepository workbenchRepository;
    private final ReviewOpinionRepository opinionRepository;
    private final ReviewModifyConfirmationRepository confirmationRepository;
    private final ReviewConfirmationIdGenerator idGenerator;
    private final Clock clock;

    public ReviewOwnerService(
            WorkbenchRepository workbenchRepository,
            ReviewOpinionRepository opinionRepository,
            ReviewModifyConfirmationRepository confirmationRepository,
            ReviewConfirmationIdGenerator idGenerator,
            Clock clock) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.opinionRepository = Objects.requireNonNull(
                opinionRepository, "opinionRepository");
        this.confirmationRepository = Objects.requireNonNull(
                confirmationRepository, "confirmationRepository");
        this.idGenerator = Objects.requireNonNull(
                idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReviewOpinionView getOpinion(
            OwnerReference actor, WorkbenchId workbenchId) {
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        ReviewOpinion opinion = requireCurrentOpinion(workbenchId);
        return ReviewOpinionView.from(
                opinion, isReadOnly(workbench, actor));
    }

    @Transactional
    public ReviewOpinionView saveOpinion(
            OwnerReference actor, SaveReviewOpinionCommand command) {
        Objects.requireNonNull(command, "command");
        requireOperableWorkbench(actor, command.getWorkbenchId());
        Optional<ReviewOpinion> current = opinionRepository.findLatest(
                command.getWorkbenchId());
        try {
            ReviewOpinion saved = current.isPresent()
                    ? current.get().revise(
                    command.getExpectedVersion(), command.getContent(),
                    actor, clock.instant())
                    : ReviewOpinion.start(
                    command.getWorkbenchId(), command.getExpectedVersion(),
                    command.getContent(), actor, clock.instant());
            opinionRepository.add(saved);
            return ReviewOpinionView.from(saved, false);
        } catch (WorkbenchDomainException failure) {
            throw translateVersionConflict(
                    failure, command.getWorkbenchId());
        }
    }

    @Transactional
    public ReviewConfirmationView confirmModification(
            OwnerReference actor,
            ConfirmReviewModificationCommand command) {
        Objects.requireNonNull(command, "command");
        requireOperableWorkbench(actor, command.getWorkbenchId());
        ReviewOpinion current = requireCurrentOpinion(
                command.getWorkbenchId());
        try {
            current.requireExact(
                    command.getOpinionVersion(), command.getOpinionHash());
            ReviewModifyConfirmation confirmation = current.confirmModify(
                    idGenerator.nextId(), command.getOpinionVersion(),
                    command.getOpinionHash(), actor, clock.instant());
            confirmationRepository.add(confirmation);
            return ReviewConfirmationView.from(confirmation, false);
        } catch (WorkbenchDomainException failure) {
            throw translateVersionConflict(
                    failure, command.getWorkbenchId());
        }
    }

    public ReviewConfirmationView getConfirmation(
            OwnerReference actor, WorkbenchId workbenchId) {
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        ReviewOpinion opinion = requireCurrentOpinion(workbenchId);
        ReviewModifyConfirmation confirmation = confirmationRepository
                .findLatest(workbenchId, opinion.getVersion(),
                        opinion.getContentHash())
                .orElseThrow(() -> new ReviewApplicationException(
                        ReviewApplicationErrorCode.CONFIRMATION_NOT_FOUND,
                        "review confirmation was not found"));
        return ReviewConfirmationView.from(
                confirmation, isReadOnly(workbench, actor));
    }

    private Workbench requireOwnedWorkbench(
            OwnerReference actor, WorkbenchId workbenchId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workbenchId, "workbenchId");
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(WorkbenchNotFoundException::new);
        try {
            workbench.requireOwnedBy(actor);
            return workbench;
        } catch (WorkbenchDomainException failure) {
            if (failure.getCode() == WorkbenchErrorCode.OWNER_REQUIRED) {
                throw new WorkbenchNotFoundException();
            }
            throw failure;
        }
    }

    private Workbench requireOperableWorkbench(
            OwnerReference actor, WorkbenchId workbenchId) {
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        workbench.requireOperableBy(actor);
        return workbench;
    }

    private ReviewOpinion requireCurrentOpinion(WorkbenchId workbenchId) {
        return opinionRepository.findLatest(workbenchId)
                .orElseThrow(() -> new ReviewApplicationException(
                        ReviewApplicationErrorCode.OPINION_NOT_FOUND,
                        "review opinion was not found"));
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

    private RuntimeException translateVersionConflict(
            WorkbenchDomainException failure, WorkbenchId workbenchId) {
        if (failure.getCode() != WorkbenchErrorCode.VERSION_CONFLICT) {
            return failure;
        }
        ReviewOpinionView current = opinionRepository.findLatest(workbenchId)
                .map(value -> ReviewOpinionView.from(value, false))
                .orElse(null);
        return new ReviewApplicationException(
                ReviewApplicationErrorCode.VERSION_CONFLICT,
                "review opinion version or hash changed", current);
    }
}
