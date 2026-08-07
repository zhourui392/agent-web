package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.app.agentrun.port.AgentHistoryMessage;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 已完成业务组装的最终 Prompt、内容哈希与历史投递方式。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PromptPayload {

    private final String finalPrompt;
    private final String promptHash;
    private final HistoryDelivery historyDelivery;
    private final List<AgentHistoryMessage> typedHistory;

    public PromptPayload(String finalPrompt, String promptHash,
                         HistoryDelivery historyDelivery) {
        this(finalPrompt, promptHash, historyDelivery,
                Collections.<AgentHistoryMessage>emptyList());
    }

    public PromptPayload(String finalPrompt, String promptHash,
                         HistoryDelivery historyDelivery,
                         List<AgentHistoryMessage> typedHistory) {
        if (finalPrompt == null || finalPrompt.trim().isEmpty()) {
            throw new IllegalArgumentException("final prompt must not be blank");
        }
        this.finalPrompt = finalPrompt;
        this.promptHash = DomainText.requireSha256(promptHash, "final prompt hash");
        if (!CanonicalHashing.sha256(finalPrompt).equals(this.promptHash)) {
            throw new IllegalArgumentException("final prompt hash does not match prompt content");
        }
        if (historyDelivery == null) {
            throw new IllegalArgumentException("history delivery must not be null");
        }
        this.historyDelivery = historyDelivery;
        if (typedHistory == null || typedHistory.contains(null)) {
            throw new IllegalArgumentException("typed history must be complete");
        }
        this.typedHistory = Collections.unmodifiableList(
                new ArrayList<AgentHistoryMessage>(typedHistory));
    }
}
