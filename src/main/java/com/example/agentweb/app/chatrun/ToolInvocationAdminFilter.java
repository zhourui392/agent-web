package com.example.agentweb.app.chatrun;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ToolInvocationAdminFilter {
    private final int page;
    private final int size;
    private final String provider;
    private final String invocationKind;
    private final String toolName;
    private final String skillName;
    private final String status;
    private final String triggerSource;
    private final String sessionId;
    private final String runId;
    private final Long startedAfter;
    private final Long startedBefore;
}
