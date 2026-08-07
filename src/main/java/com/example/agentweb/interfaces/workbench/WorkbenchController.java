package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.CreateWorkbenchCommand;
import com.example.agentweb.app.workbench.WorkbenchCreationAppService;
import com.example.agentweb.app.workbench.WorkbenchCreationResult;
import com.example.agentweb.app.workbench.WorkbenchLifecycleAppService;
import com.example.agentweb.app.workbench.WorkbenchLifecycleResult;
import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.WorkbenchStageLifecycleResult;
import com.example.agentweb.app.workbench.query.WorkbenchDetailView;
import com.example.agentweb.app.workbench.query.WorkbenchListCursor;
import com.example.agentweb.app.workbench.query.WorkbenchListPage;
import com.example.agentweb.app.workbench.query.WorkbenchListRequest;
import com.example.agentweb.app.workbench.query.WorkbenchQueryService;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
import com.example.agentweb.interfaces.workbench.dto.CreateWorkbenchRequest;
import com.example.agentweb.interfaces.workbench.dto.WorkbenchCreationResponse;
import com.example.agentweb.interfaces.workbench.dto.WorkbenchLifecycleResponse;
import com.example.agentweb.interfaces.workbench.dto.WorkbenchStageLifecycleResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Locale;

/**
 * Workbench Owner 侧 HTTP 边界，负责认证用户、查询参数、Header 与 DTO 转换。
 *
 * @author alex
 * @since 2026-08-01
 */
@RestController
@RequestMapping(path = "/api/workbenches", produces = MediaType.APPLICATION_JSON_VALUE)
public class WorkbenchController {

    private static final int DEFAULT_LIST_LIMIT = 20;

    private final WorkbenchCreationAppService appService;
    private final WorkbenchQueryService queryService;
    private final WorkbenchLifecycleAppService lifecycleAppService;
    private final CurrentUserProvider currentUserProvider;

    public WorkbenchController(WorkbenchCreationAppService appService,
                               WorkbenchQueryService queryService,
                               WorkbenchLifecycleAppService lifecycleAppService,
                               CurrentUserProvider currentUserProvider) {
        this.appService = appService;
        this.queryService = queryService;
        this.lifecycleAppService = lifecycleAppService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public ResponseEntity<WorkbenchCreationResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateWorkbenchRequest request) {
        OwnerReference actor = OwnerReference.of(
                currentUserProvider.currentUserId(),
                currentUserProvider.currentUserName());
        CreateWorkbenchCommand command = new CreateWorkbenchCommand(
                idempotencyKey, request.getTitle(), request.getOriginalGoal(),
                AgentType.parseKnown(request.getAgentType()), request.getEnvironment(),
                request.getWorkspaceRoot(), request.getPrimaryRepository(),
                request.getRepositories(), request.getStageDefinitionIdentifiers(),
                request.getExpectedStageCatalogVersion(),
                request.isUseWorktree());
        WorkbenchCreationResult result = appService.create(actor, command);
        return ResponseEntity.created(
                        URI.create("/api/workbenches/" + result.getWorkbenchId()))
                .body(WorkbenchCreationResponse.from(result));
    }

    @GetMapping
    public WorkbenchListPage list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "cursorUpdatedAt", required = false)
                    Long cursorUpdatedAt,
            @RequestParam(value = "cursorWorkbenchId", required = false)
                    String cursorWorkbenchId,
            @RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIST_LIMIT)
                    int limit) {
        WorkbenchListRequest request = new WorkbenchListRequest(
                parseStatus(status),
                parseCursor(cursorUpdatedAt, cursorWorkbenchId), limit);
        return queryService.listByOwner(currentUserProvider.currentUserId(), request);
    }

    @GetMapping("/{workbenchId}")
    public WorkbenchDetailView detail(
            @PathVariable("workbenchId") String workbenchId) {
        return queryService.findDetailByOwner(
                        currentUserProvider.currentUserId(), workbenchId)
                .orElseThrow(WorkbenchNotFoundException::new);
    }

    @PostMapping("/{workbenchId}/archive")
    public WorkbenchLifecycleResponse archive(
            @PathVariable("workbenchId") String workbenchId,
            @RequestHeader("If-Match") long expectedVersion) {
        WorkbenchLifecycleResult result = lifecycleAppService.archive(
                currentOwner(), WorkbenchId.of(workbenchId), expectedVersion);
        return WorkbenchLifecycleResponse.from(result);
    }

    @PostMapping("/{workbenchId}/stages/{stageInstanceIdentifier}/complete")
    public WorkbenchStageLifecycleResponse completeStage(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("stageInstanceIdentifier")
                    String stageInstanceIdentifier,
            @RequestHeader("If-Match") long expectedVersion) {
        WorkbenchStageLifecycleResult result = lifecycleAppService.completeStage(
                currentOwner(), WorkbenchId.of(workbenchId),
                stageInstanceIdentifier, expectedVersion);
        return WorkbenchStageLifecycleResponse.from(result);
    }

    @PostMapping("/{workbenchId}/stages/{stageInstanceIdentifier}/reopen")
    public WorkbenchStageLifecycleResponse reopenStage(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("stageInstanceIdentifier")
                    String stageInstanceIdentifier,
            @RequestHeader("If-Match") long expectedVersion) {
        WorkbenchStageLifecycleResult result = lifecycleAppService.reopenStage(
                currentOwner(), WorkbenchId.of(workbenchId),
                stageInstanceIdentifier, expectedVersion);
        return WorkbenchStageLifecycleResponse.from(result);
    }

    private OwnerReference currentOwner() {
        return OwnerReference.of(
                currentUserProvider.currentUserId(),
                currentUserProvider.currentUserName());
    }

    private WorkbenchStatus parseStatus(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return WorkbenchStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private WorkbenchListCursor parseCursor(
            Long updatedAt, String workbenchId) {
        if (updatedAt == null && workbenchId == null) {
            return null;
        }
        if (updatedAt == null || workbenchId == null
                || workbenchId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "cursorUpdatedAt and cursorWorkbenchId must be provided together");
        }
        return new WorkbenchListCursor(updatedAt.longValue(), workbenchId);
    }

}
