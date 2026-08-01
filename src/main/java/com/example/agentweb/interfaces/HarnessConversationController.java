package com.example.agentweb.interfaces;

import com.example.agentweb.app.harness.HarnessConversationMessageView;
import com.example.agentweb.app.harness.HarnessConversationQueryService;
import com.example.agentweb.app.harness.HarnessConversationService;
import com.example.agentweb.app.harness.HarnessConversationTurnResult;
import com.example.agentweb.app.harness.HarnessRetirementPolicy;
import com.example.agentweb.app.harness.StartHarnessConversationCommand;
import com.example.agentweb.interfaces.dto.HarnessConversationRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Harness 阶段对话与修订 API。
 *
 * @author alex
 * @since 2026-07-24
 */
@RestController
@RequestMapping(path = "/api/harness/runs", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessConversationController {

    private final HarnessConversationService conversationService;
    private final HarnessConversationQueryService queryService;
    private final HarnessRetirementPolicy retirementPolicy;

    public HarnessConversationController(HarnessConversationService conversationService,
                                         HarnessConversationQueryService queryService,
                                         HarnessRetirementPolicy retirementPolicy) {
        this.conversationService = conversationService;
        this.queryService = queryService;
        this.retirementPolicy = retirementPolicy;
    }

    @PostMapping("/{runId}/stages/{stage}/conversation")
    public ResponseEntity<HarnessConversationTurnResult> send(
            @PathVariable("runId") String runId,
            @PathVariable("stage") String stage,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody HarnessConversationRequest request) {
        retirementPolicy.requireMutationAvailable();
        StartHarnessConversationCommand command = new StartHarnessConversationCommand(
                runId, HarnessControllerSupport.stage(stage), HarnessControllerSupport.requireIdempotencyKey(idempotencyKey), request.getMessage());
        return ResponseEntity.accepted().body(conversationService.send(command));
    }

    @GetMapping("/{runId}/conversation")
    public List<HarnessConversationMessageView> list(@PathVariable("runId") String runId) {
        return queryService.list(runId);
    }

}
