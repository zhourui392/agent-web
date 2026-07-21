package com.example.agentweb.infra;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AgentRun execution and recall switches.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "agent.run")
public class AgentRunProperties {

    /** Master switch for workspace context summary injection. */
    private boolean workspaceContextEnabled = true;
    /** Master switch for workspace index keyword pre-recall. */
    private boolean workspaceKnowledgeEnabled = true;
    /** Default topK used by AgentRun recall contributors. */
    private int recallTopK = 8;
}
