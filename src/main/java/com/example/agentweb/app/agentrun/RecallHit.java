package com.example.agentweb.app.agentrun;

import lombok.Getter;

/**
 * One recall hit in AgentRun prompt assembly observation.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Getter
public class RecallHit {

    private final String sourceId;
    private final String sourceType;
    private final String path;
    private final int rankNo;

    public RecallHit(String sourceId, String sourceType, String path, int rankNo) {
        this.sourceId = sourceId;
        this.sourceType = sourceType;
        this.path = path;
        this.rankNo = rankNo;
    }
}
