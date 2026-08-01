package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.conversation.PhaseConversationAppService;
import com.example.agentweb.app.workbench.conversation.PhaseConversationResult;
import com.example.agentweb.app.workbench.conversation.RestartPhaseConversationCommand;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.interfaces.workbench.dto.PhaseConversationRestartResponse;
import com.example.agentweb.interfaces.workbench.dto.PhaseConversationEnsureResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Phase Conversation restart 的 HTTP 边界。
 *
 * @author alex
 * @since 2026-08-01
 */
@RestController
@RequestMapping(path = "/api/workbenches/{workbenchId}/phases/{phase}/conversation",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class PhaseConversationController {

    private final PhaseConversationAppService appService;
    private final CurrentUserProvider currentUserProvider;

    public PhaseConversationController(
            PhaseConversationAppService appService,
            CurrentUserProvider currentUserProvider) {
        this.appService = appService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public PhaseConversationEnsureResponse ensure(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("phase") String phase,
            @RequestHeader("If-Match") long expectedVersion) {
        PhaseConversationResult result = appService.ensureConversation(
                currentOwner(), WorkbenchId.of(workbenchId),
                parsePhase(phase), expectedVersion);
        return PhaseConversationEnsureResponse.from(result);
    }

    @PostMapping("/restart")
    public PhaseConversationRestartResponse restart(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("phase") String phase,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("If-Match") long expectedVersion) {
        RestartPhaseConversationCommand command = new RestartPhaseConversationCommand(
                WorkbenchId.of(workbenchId), parsePhase(phase),
                idempotencyKey, expectedVersion);
        PhaseConversationResult result = appService.restartConversation(
                currentOwner(), command);
        return PhaseConversationRestartResponse.from(result);
    }

    private OwnerReference currentOwner() {
        return OwnerReference.of(
                currentUserProvider.currentUserId(),
                currentUserProvider.currentUserName());
    }

    private WorkbenchPhase parsePhase(String phase) {
        return WorkbenchPhase.valueOf(phase.trim().toUpperCase(Locale.ROOT));
    }
}
