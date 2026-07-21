package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * @author zhourui(V33215020)
 */
@Getter
@Setter
public class StartSessionResponse {
    private String sessionId;
    private String agentType;
    private String workingDir;
    private String env;

    public StartSessionResponse(String sessionId, String agentType, String workingDir) {
        this(sessionId, agentType, workingDir, null);
    }

    public StartSessionResponse(String sessionId, String agentType, String workingDir, String env) {
        this.sessionId = sessionId;
        this.agentType = agentType;
        this.workingDir = workingDir;
        this.env = env;
    }
}
