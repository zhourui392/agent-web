package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.conversation.RestartWorkbenchStageConversationCommand;
import com.example.agentweb.app.workbench.conversation.WorkbenchStageConversationAppService;
import com.example.agentweb.app.workbench.conversation.WorkbenchStageConversationResult;
import com.example.agentweb.app.workbench.query.WorkbenchQueryService;
import com.example.agentweb.app.workbench.query.WorkbenchStageConversationMessagePage;
import com.example.agentweb.app.workbench.query.WorkbenchStageConversationMessageRequest;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.interfaces.workbench.dto.WorkbenchStageConversationEnsureResponse;
import com.example.agentweb.interfaces.workbench.dto.WorkbenchStageConversationRestartResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 动态 Stage Conversation 的 Owner HTTP 边界。
 *
 * @author alex
 * @since 2026-08-05
 */
@RestController
@RequestMapping(
        path = "/api/workbenches/{workbenchId}/stages/"
                + "{stageInstanceIdentifier}/conversation",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class WorkbenchStageConversationController {

    private static final int DEFAULT_MESSAGE_PAGE_LIMIT = 50;

    private final WorkbenchStageConversationAppService appService;
    private final WorkbenchQueryService queryService;
    private final CurrentUserProvider currentUserProvider;

    public WorkbenchStageConversationController(
            WorkbenchStageConversationAppService appService,
            WorkbenchQueryService queryService,
            CurrentUserProvider currentUserProvider) {
        this.appService = appService;
        this.queryService = queryService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/messages")
    public WorkbenchStageConversationMessagePage messages(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("stageInstanceIdentifier")
                    String stageInstanceIdentifier,
            @RequestParam(value = "beforeMessageId", required = false)
                    Long beforeMessageId,
            @RequestParam(
                    value = "limit",
                    defaultValue = "" + DEFAULT_MESSAGE_PAGE_LIMIT)
                    int limit) {
        return queryService.findCurrentStageConversationByOwner(
                        currentUserProvider.currentUserId(), workbenchId,
                        stageInstanceIdentifier,
                        new WorkbenchStageConversationMessageRequest(
                                beforeMessageId, limit))
                .orElseThrow(WorkbenchNotFoundException::new);
    }

    @PostMapping
    public WorkbenchStageConversationEnsureResponse ensure(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("stageInstanceIdentifier")
                    String stageInstanceIdentifier,
            @RequestHeader("If-Match") long expectedVersion) {
        WorkbenchStageConversationResult result =
                appService.ensureConversation(
                        currentOwner(), WorkbenchId.of(workbenchId),
                        stageInstanceIdentifier, expectedVersion);
        return WorkbenchStageConversationEnsureResponse.from(result);
    }

    @PostMapping("/restart")
    public WorkbenchStageConversationRestartResponse restart(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("stageInstanceIdentifier")
                    String stageInstanceIdentifier,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("If-Match") long expectedVersion) {
        RestartWorkbenchStageConversationCommand command =
                new RestartWorkbenchStageConversationCommand(
                        WorkbenchId.of(workbenchId), stageInstanceIdentifier,
                        idempotencyKey, expectedVersion);
        WorkbenchStageConversationResult result =
                appService.restartConversation(currentOwner(), command);
        return WorkbenchStageConversationRestartResponse.from(result);
    }

    private OwnerReference currentOwner() {
        return OwnerReference.of(
                currentUserProvider.currentUserId(),
                currentUserProvider.currentUserName());
    }
}
