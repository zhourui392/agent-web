package com.example.agentweb.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for CLI agents.
 * Users can override by application.yml or environment variables.
 */
@Component
@ConfigurationProperties(prefix = "agent.cli")
public class AgentCliProperties {

    private final Client codex = new Client();
    private final Client claude = new Client();

    public Client getCodex() {
        return codex;
    }

    public Client getClaude() {
        return claude;
    }

    public static class Client {
        /** Executable path, e.g. "codex" or "/usr/local/bin/claude" */
        private String exec;
        /** Arguments template; use ${MESSAGE} placeholder for user message if needed. */
        private List<String> args = new ArrayList<String>();
        /** If true, write user message to stdin of the process. */
        private boolean stdin = true;
        /** Max execution time in seconds. */
        private int timeoutSeconds = 120;

        public String getExec() {
            return exec;
        }

        public void setExec(String exec) {
            this.exec = exec;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args;
        }

        public boolean isStdin() {
            return stdin;
        }

        public void setStdin(boolean stdin) {
            this.stdin = stdin;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
