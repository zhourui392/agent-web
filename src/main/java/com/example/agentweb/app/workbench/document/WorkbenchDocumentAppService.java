package com.example.agentweb.app.workbench.document;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.WorkspaceFailureCode;
import com.example.agentweb.app.workbench.WorkspaceOperationException;
import com.example.agentweb.app.workbench.document.port.ScopedDocumentGateway;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Owner 可见 Workbench 的 Scoped Document 查询编排。
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
@Transactional(readOnly = true)
public class WorkbenchDocumentAppService {

    private final WorkbenchRepository workbenchRepository;
    private final ScopedDocumentGateway gateway;
    private final WorkbenchTelemetry telemetry;

    public WorkbenchDocumentAppService(
            WorkbenchRepository workbenchRepository,
            ScopedDocumentGateway gateway,
            WorkbenchTelemetry telemetry) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public DocumentDirectoryView listTree(
            OwnerReference actor, WorkbenchId workbenchId,
            DocumentDirectoryQuery query) {
        Objects.requireNonNull(query, "query");
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        try {
            return gateway.listTree(workbench.getRepositoryScope(), query);
        } catch (RuntimeException failure) {
            recordScopeViolation(failure);
            throw failure;
        }
    }

    public DocumentContentView readContent(
            OwnerReference actor, WorkbenchId workbenchId,
            DocumentReference reference) {
        Objects.requireNonNull(reference, "reference");
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        DocumentContentView content;
        try {
            content = gateway.readContent(
                    workbench.getRepositoryScope(), reference);
        } catch (RuntimeException failure) {
            recordScopeViolation(failure);
            telemetry.documentRead(null, "FAILED");
            throw failure;
        }
        telemetry.documentRead(content.getKind(), "SUCCESS");
        return content;
    }

    public DocumentDownloadView download(
            OwnerReference actor, WorkbenchId workbenchId,
            DocumentReference reference) {
        Objects.requireNonNull(reference, "reference");
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        try {
            return gateway.download(workbench.getRepositoryScope(), reference);
        } catch (RuntimeException failure) {
            recordScopeViolation(failure);
            throw failure;
        }
    }

    public DocumentDownloadView inlineImage(
            OwnerReference actor, WorkbenchId workbenchId,
            DocumentReference reference) {
        Objects.requireNonNull(reference, "reference");
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        try {
            return gateway.inlineImage(
                    workbench.getRepositoryScope(), reference);
        } catch (RuntimeException failure) {
            recordScopeViolation(failure);
            throw failure;
        }
    }

    private Workbench requireOwnedWorkbench(
            OwnerReference actor, WorkbenchId workbenchId) {
        if (actor == null || workbenchId == null) {
            throw new IllegalArgumentException(
                    "document actor and workbench id are required");
        }
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(WorkbenchNotFoundException::new);
        try {
            workbench.requireOwnedBy(actor);
            return workbench;
        } catch (WorkbenchDomainException ex) {
            if (ex.getCode() == WorkbenchErrorCode.OWNER_REQUIRED) {
                throw new WorkbenchNotFoundException();
            }
            throw ex;
        }
    }

    private void recordScopeViolation(RuntimeException failure) {
        if (failure instanceof WorkspaceOperationException
                && isScopeSecurityFailure(
                ((WorkspaceOperationException) failure).getCode())) {
            telemetry.workspaceScopeViolation();
        }
    }

    private boolean isScopeSecurityFailure(WorkspaceFailureCode code) {
        return code == WorkspaceFailureCode.REPOSITORY_SCOPE_VIOLATION
                || code == WorkspaceFailureCode.WORKSPACE_PATH_FORBIDDEN;
    }
}
