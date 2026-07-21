package com.example.agentweb.app.agentrun;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unified recall observation model for one AgentRun prompt assembly channel.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Getter
public class RecallContribution {

    private final RecallChannel channel;
    private final boolean enabled;
    private final Boolean used;
    private final int hitCount;
    private final int topK;
    private final List<RecallHit> hits;

    public RecallContribution(RecallChannel channel,
                              boolean enabled,
                              Boolean used,
                              int topK,
                              List<RecallHit> hits) {
        this.channel = channel;
        this.enabled = enabled;
        this.used = used;
        this.topK = topK;
        this.hits = hits == null ? Collections.<RecallHit>emptyList()
                : Collections.unmodifiableList(new ArrayList<RecallHit>(hits));
        this.hitCount = this.hits.size();
    }

    public static RecallContribution disabled(RecallChannel channel) {
        return new RecallContribution(channel, false, null, 0, Collections.<RecallHit>emptyList());
    }

    public static RecallContribution miss(RecallChannel channel, int topK) {
        return new RecallContribution(channel, true, Boolean.FALSE, topK, Collections.<RecallHit>emptyList());
    }

    public static RecallContribution notApplicable(RecallChannel channel, int topK) {
        return new RecallContribution(channel, true, null, topK, Collections.<RecallHit>emptyList());
    }

    public static RecallContribution hit(RecallChannel channel, int topK, List<RecallHit> hits) {
        return new RecallContribution(channel, true, Boolean.TRUE, topK, hits);
    }
}
