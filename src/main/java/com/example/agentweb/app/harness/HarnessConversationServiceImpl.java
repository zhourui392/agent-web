package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.HarnessCommandId;
import com.example.agentweb.domain.harness.StageCapabilityPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * 阶段对话应用编排：准备 Attempt、固化安全默认能力快照并启动 Runtime。
 *
 * @author alex
 * @since 2026-07-24
 */
@Service
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessConversationServiceImpl implements HarnessConversationService {

    private final HarnessConversationPreparer preparer;
    private final HarnessCapabilityService capabilityService;
    private final HarnessExecutionService executionService;

    public HarnessConversationServiceImpl(HarnessConversationPreparer preparer,
                                          HarnessCapabilityService capabilityService,
                                          HarnessExecutionService executionService) {
        this.preparer = preparer;
        this.capabilityService = capabilityService;
        this.executionService = executionService;
    }

    @Override
    public HarnessConversationTurnResult send(StartHarnessConversationCommand command) {
        PreparedHarnessConversation prepared = preparer.prepare(command);
        capabilityService.resolve(new ResolveHarnessCapabilityCommand(
                prepared.getRunId(), prepared.getStage(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                StageCapabilityPolicy.conversationGrant(prepared.getStage()),
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), command.getMessage()));
        String executionKey = HarnessCommandId.conversationExecution(
                prepared.getRunId(), prepared.getStage(), command.getIdempotencyKey());
        HarnessExecutionResult execution = executionService.start(
                new StartHarnessExecutionCommand(prepared.getRunId(),
                        prepared.getStage(), executionKey));
        return new HarnessConversationTurnResult(execution, prepared.isDuplicated());
    }
}
