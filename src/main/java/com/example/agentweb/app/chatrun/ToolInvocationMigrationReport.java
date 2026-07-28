package com.example.agentweb.app.chatrun;

import lombok.Getter;

@Getter
public class ToolInvocationMigrationReport {
    private long scannedAssistantMessages;
    private long recognizedCallStarts;
    private long insertedToolUses;
    private long insertedCommandExecutions;
    private long insertedSkills;
    private long parsedInputs;
    private long invalidInputs;
    private long matchedResults;
    private long replayedResultsIgnored;
    private long incompleteCalls;
    private long failedCalls;
    private long resolvedSkillNames;
    private long missingSkillNames;

    void scannedMessage() { scannedAssistantMessages++; }
    void call(com.example.agentweb.domain.chatrun.ToolInvocationKind kind) {
        recognizedCallStarts++;
        if (kind == com.example.agentweb.domain.chatrun.ToolInvocationKind.SKILL) insertedSkills++;
        else if (kind == com.example.agentweb.domain.chatrun.ToolInvocationKind.COMMAND_EXECUTION) insertedCommandExecutions++;
        else insertedToolUses++;
    }
    void parsedInput() { parsedInputs++; }
    void invalidInput() { invalidInputs++; }
    void matchedResult(boolean failed) { matchedResults++; if (failed) failedCalls++; }
    void replayedResult() { replayedResultsIgnored++; }
    void incomplete() { incompleteCalls++; }
    void skillName(boolean resolved) { if (resolved) resolvedSkillNames++; else missingSkillNames++; }

    @Override
    public String toString() {
        return "ToolInvocationMigrationReport{" +
                "scannedAssistantMessages=" + scannedAssistantMessages +
                ", recognizedCallStarts=" + recognizedCallStarts +
                ", insertedToolUses=" + insertedToolUses +
                ", insertedCommandExecutions=" + insertedCommandExecutions +
                ", insertedSkills=" + insertedSkills +
                ", parsedInputs=" + parsedInputs +
                ", invalidInputs=" + invalidInputs +
                ", matchedResults=" + matchedResults +
                ", replayedResultsIgnored=" + replayedResultsIgnored +
                ", incompleteCalls=" + incompleteCalls +
                ", failedCalls=" + failedCalls +
                ", resolvedSkillNames=" + resolvedSkillNames +
                ", missingSkillNames=" + missingSkillNames + '}';
    }
}
