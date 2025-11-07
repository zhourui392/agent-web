package com.example.agentweb.adapter;

import com.example.agentweb.domain.AgentType;

import java.io.IOException;

/**
 * Port for sending a prompt to a specific CLI agent implementation.
 */
public interface AgentGateway {

    /**
     * Execute one prompt against the selected agent in the given working directory.
     * @param type Agent type
     * @param workingDir Working directory (must exist)
     * @param userMessage User prompt content
     * @return stdout/stderr merged output
     */
    String runOnce(AgentType type, String workingDir, String userMessage) throws IOException, InterruptedException;

    /**
     * Stream output chunks as the agent runs. Implementations should invoke onChunk for each
     * available stdout/stderr chunk and call onExit with the final exit code (or -1 on timeout).
     * This method should block until the process exits.
     * @param resumeId Optional resume ID for continuing a conversation (used for Claude --resume)
     */
    void runStream(AgentType type,
                   String workingDir,
                   String userMessage,
                   String resumeId,
                   java.util.function.Consumer<String> onChunk,
                   java.util.function.IntConsumer onExit) throws IOException, InterruptedException;
}
