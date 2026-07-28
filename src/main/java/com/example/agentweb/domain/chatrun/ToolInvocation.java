package com.example.agentweb.domain.chatrun;

import com.example.agentweb.domain.shared.AgentType;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder(toBuilder = true)
public class ToolInvocation {

    private final Long id;
    private final String sessionId;
    private final String runId;
    private final Long assistantMessageId;
    private final AgentType provider;
    private final String providerCallId;
    private final int invocationIndex;
    private final ToolInvocationKind invocationKind;
    private final String toolName;
    private final String skillName;
    private final ToolInvocationTriggerSource triggerSource;
    private final String inputJson;
    private final String outputText;
    private final ToolInvocationStatus status;
    private final boolean error;
    private final Integer exitCode;
    private final String providerItemType;
    private final String providerStatus;
    private final boolean inputTruncated;
    private final boolean outputTruncated;
    private final Integer outputOriginalSize;
    private final Long startedAt;
    private final Long completedAt;
    private final long createdAt;
    private final long updatedAt;
    private final ToolInvocationSource source;
    private final Long sourceMessageId;
    private final String migrationConfidence;

    public void validate() {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (provider == null || invocationKind == null || triggerSource == null
                || status == null || source == null || invocationIndex < 1) {
            throw new IllegalArgumentException("tool invocation metadata is incomplete");
        }
        if (invocationKind == ToolInvocationKind.COMMAND_EXECUTION
                && (toolName != null || skillName != null)) {
            throw new IllegalArgumentException("command execution cannot have tool or skill name");
        }
        if (invocationKind == ToolInvocationKind.TOOL_USE
                && (toolName == null || skillName != null)) {
            throw new IllegalArgumentException("tool use requires toolName and forbids skillName");
        }
        if (invocationKind == ToolInvocationKind.SKILL && !"Skill".equals(toolName)) {
            throw new IllegalArgumentException("skill invocation must use Skill toolName");
        }
    }
}
