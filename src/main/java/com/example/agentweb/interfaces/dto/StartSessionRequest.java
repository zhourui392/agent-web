package com.example.agentweb.interfaces.dto;

import javax.validation.constraints.NotBlank;

public class StartSessionRequest {
    @NotBlank
    private String agentType; // CODEX or CLAUDE
    @NotBlank
    private String workingDir;

    public String getAgentType() {
        return agentType;
    }

    public void setAgentType(String agentType) {
        this.agentType = agentType;
    }

    public String getWorkingDir() {
        return workingDir;
    }

    public void setWorkingDir(String workingDir) {
        this.workingDir = workingDir;
    }
}
