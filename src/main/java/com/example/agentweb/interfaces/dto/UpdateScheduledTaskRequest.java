package com.example.agentweb.interfaces.dto;

public class UpdateScheduledTaskRequest {
    private String name;
    private String cronExpr;
    private String prompt;
    private String agentType;
    private String workingDir;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCronExpr() { return cronExpr; }
    public void setCronExpr(String cronExpr) { this.cronExpr = cronExpr; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getAgentType() { return agentType; }
    public void setAgentType(String agentType) { this.agentType = agentType; }
    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }
}
