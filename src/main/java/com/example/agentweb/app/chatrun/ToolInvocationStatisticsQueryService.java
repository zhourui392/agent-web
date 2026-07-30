package com.example.agentweb.app.chatrun;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * @author alex
 */
public interface ToolInvocationStatisticsQueryService {
    Overview overview(Filter filter);
    List<DailyPoint> dailyTrend(Filter filter);
    Page<RankingRow> rankings(Filter filter, RankingType type, RankingOrder order, int page, int size);
    Page<ConversationRow> conversations(Filter filter, ConversationOrder order, int page, int size);

    enum RankingType { TOOL, COMMAND, SKILL }
    enum RankingOrder { INVOCATION_COUNT_DESC, CONVERSATION_COUNT_DESC, FAILED_COUNT_DESC, FAILURE_RATE_DESC }
    enum ConversationOrder { INVOCATION_COUNT_DESC, FAILED_COUNT_DESC, FAILURE_RATE_DESC }

    @Getter
    @Builder
    final class Filter {
        private final Long startedAfter;
        private final Long startedBefore;
        private final String provider;
        private final String invocationKind;
        private final String status;
        private final String source;
        private final String triggerSource;
        private final String analysisName;
        private final String sessionId;
        private final String runId;
    }

    @Getter
    @Builder
    final class Overview {
        private final long invocationCount;
        private final long conversationCount;
        private final Double averageInvocationsPerConversation;
        private final long terminalCount;
        private final long succeededCount;
        private final long failedCount;
        private final long incompleteCount;
        private final long startedCount;
        private final long unknownCount;
        private final Double successRate;
        private final Double failureRate;
        private final Double incompleteRate;
        private final long claudeCount;
        private final long codexCount;
        private final long nativeCount;
        private final long toolUseCount;
        private final long commandExecutionCount;
        private final long skillCount;
        private final long inputTruncatedCount;
        private final long outputTruncatedCount;
        private final long liveCount;
        private final long historyMigrationCount;
        private final long durationAvailableCount;
    }

    @Getter
    @Builder
    final class DailyPoint {
        private final String date;
        private final long invocationCount;
        private final long conversationCount;
        private final long succeededCount;
        private final long failedCount;
        private final long incompleteCount;
        private final long claudeCount;
        private final long codexCount;
    }

    @Getter
    @Builder
    final class RankingRow {
        private final String analysisName;
        private final String invocationKind;
        private final long invocationCount;
        private final long conversationCount;
        private final long terminalCount;
        private final long succeededCount;
        private final long failedCount;
        private final long incompleteCount;
        private final long unknownCount;
        private final Double failureRate;
        private final long inputTruncatedCount;
        private final long outputTruncatedCount;
    }

    @Getter
    @Builder
    final class ConversationRow {
        private final String sessionId;
        private final String title;
        private final String userId;
        private final String userName;
        private final String agentType;
        private final long invocationCount;
        private final long distinctAnalysisNameCount;
        private final long skillCount;
        private final long succeededCount;
        private final long failedCount;
        private final long incompleteCount;
        private final long terminalCount;
        private final Double failureRate;
        private final Long firstInvocationAt;
        private final Long lastInvocationAt;
    }

    @Getter
    final class Page<T> {
        private final List<T> items;
        private final long total;
        private final int page;
        private final int size;
        public Page(List<T> items, long total, int page, int size) {
            this.items = items; this.total = total; this.page = page; this.size = size;
        }
    }
}
