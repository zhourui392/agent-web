package com.example.agentweb.interfaces.workbench.admin;

import com.example.agentweb.app.workbench.admin.AdminWorkbenchDetailView;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchListCursor;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchListPage;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchListRequest;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchQueryService;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunActionResult;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunAppService;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunDetailView;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunListCursor;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunListPage;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunListRequest;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunNotFoundException;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.workbench.WorkbenchAdministrator;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
import com.example.agentweb.interfaces.workbench.admin.dto.AdminWorkbenchActionRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * 独立 Admin Workbench 查询、异常 Run 停止与显式对账边界。
 *
 * <p>接口只提供管理员查询、停止和对账，不接收 Owner 身份，也不提交 Run。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@RestController
@RequestMapping(path = "/api/admin/workbenches",
        produces = MediaType.APPLICATION_JSON_VALUE)
public final class AdminWorkbenchController {

    private final AdminWorkbenchQueryService queryService;
    private final AdminWorkbenchRunAppService runAppService;
    private final UserContext userContext;

    public AdminWorkbenchController(
            AdminWorkbenchQueryService queryService,
            AdminWorkbenchRunAppService runAppService,
            UserContext userContext) {
        this.queryService = queryService;
        this.runAppService = runAppService;
        this.userContext = userContext;
    }

    @GetMapping
    public AdminWorkbenchListPage list(
            @RequestParam(value = "status", required = false)
                    String status,
            @RequestParam(value = "cursorUpdatedAt", required = false)
                    Long cursorUpdatedAt,
            @RequestParam(value = "cursorWorkbenchId", required = false)
                    String cursorWorkbenchId,
            @RequestParam(value = "limit", defaultValue = "20")
                    int limit) {
        currentAdministrator();
        return queryService.list(new AdminWorkbenchListRequest(
                parseWorkbenchStatus(status),
                parseWorkbenchCursor(cursorUpdatedAt, cursorWorkbenchId),
                limit));
    }

    @GetMapping("/{workbenchId}")
    public AdminWorkbenchDetailView detail(
            @PathVariable("workbenchId") String workbenchId) {
        currentAdministrator();
        return queryService.findDetail(workbenchId)
                .orElseThrow(AdminWorkbenchNotFoundException::new);
    }

    @GetMapping("/{workbenchId}/runs")
    public AdminWorkbenchRunListPage listRuns(
            @PathVariable("workbenchId") String workbenchId,
            @RequestParam(value = "status", required = false)
                    String status,
            @RequestParam(value = "cursorCreatedAt", required = false)
                    Long cursorCreatedAt,
            @RequestParam(value = "cursorRunId", required = false)
                    String cursorRunId,
            @RequestParam(value = "limit", defaultValue = "20")
                    int limit) {
        currentAdministrator();
        return queryService.listRuns(
                workbenchId, new AdminWorkbenchRunListRequest(
                        parseRunStatus(status),
                        parseRunCursor(cursorCreatedAt, cursorRunId),
                        limit));
    }

    @GetMapping("/{workbenchId}/runs/{runId}")
    public AdminWorkbenchRunDetailView runDetail(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("runId") String runId) {
        currentAdministrator();
        return queryService.findRunDetail(workbenchId, runId)
                .orElseThrow(AdminWorkbenchRunNotFoundException::new);
    }

    @PostMapping(path = "/{workbenchId}/runs/{runId}/stop",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminWorkbenchRunActionResult> stop(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("runId") String runId,
            @RequestBody AdminWorkbenchActionRequest request) {
        requireActionRequest(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                runAppService.stop(
                        currentAdministrator(),
                        WorkbenchId.of(workbenchId), runId));
    }

    @PostMapping(path = "/{workbenchId}/runs/{runId}/reconcile",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public AdminWorkbenchRunActionResult reconcile(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("runId") String runId,
            @RequestBody AdminWorkbenchActionRequest request) {
        requireActionRequest(request);
        return runAppService.reconcile(
                currentAdministrator(), WorkbenchId.of(workbenchId),
                runId);
    }

    private WorkbenchAdministrator currentAdministrator() {
        LoginUser user = userContext.currentUser()
                .orElseThrow(AdminWorkbenchUnauthorizedException::new);
        if (!user.isAdmin()) {
            throw new AdminWorkbenchForbiddenException();
        }
        return WorkbenchAdministrator.fromAuthenticated(user);
    }

    private void requireActionRequest(
            AdminWorkbenchActionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "admin workbench action request is required");
        }
    }

    private WorkbenchStatus parseWorkbenchStatus(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return WorkbenchStatus.valueOf(
                value.trim().toUpperCase(Locale.ROOT));
    }

    private ChatRunStatus parseRunStatus(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return ChatRunStatus.valueOf(
                value.trim().toUpperCase(Locale.ROOT));
    }

    private AdminWorkbenchListCursor parseWorkbenchCursor(
            Long updatedAt, String workbenchId) {
        if (updatedAt == null && workbenchId == null) {
            return null;
        }
        if (updatedAt == null || workbenchId == null) {
            throw new IllegalArgumentException(
                    "admin workbench cursor fields must be provided together");
        }
        return new AdminWorkbenchListCursor(
                updatedAt.longValue(), workbenchId);
    }

    private AdminWorkbenchRunListCursor parseRunCursor(
            Long createdAt, String runId) {
        if (createdAt == null && runId == null) {
            return null;
        }
        if (createdAt == null || runId == null) {
            throw new IllegalArgumentException(
                    "admin workbench run cursor fields must be provided together");
        }
        return new AdminWorkbenchRunListCursor(
                createdAt.longValue(), runId);
    }
}
