package com.example.agentweb.interfaces.dto;

public class StartSessionResponse {
    private String sessionId;
    private String agentType;
    private String workingDir;

    public StartSessionResponse(String sessionId, String agentType, String workingDir) {
        this.sessionId = sessionId;
        this.agentType = agentType;
        this.workingDir = workingDir;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getAgentType() { return agentType; }
    public void setAgentType(String agentType) { this.agentType = agentType; }

    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }
}
