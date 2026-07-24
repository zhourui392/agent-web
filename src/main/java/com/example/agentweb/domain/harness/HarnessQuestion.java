package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.time.Instant;

/**
 * Stage Attempt 中需要人工补充的信息；问题和回答均作为不可丢失的审计事实保存。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class HarnessQuestion {

    private final String questionId;
    private final HarnessStage stage;
    private final int attempt;
    private final String question;
    private final boolean blocking;
    private final String askedBy;
    private final Instant askedAt;
    private String answer;
    private String answeredBy;
    private Instant answeredAt;

    private HarnessQuestion(String questionId, HarnessStage stage, int attempt,
                            String question, boolean blocking, String askedBy,
                            Instant askedAt, String answer, String answeredBy,
                            Instant answeredAt) {
        this.questionId = DomainText.require(questionId, "question id", 128);
        if (stage == null || attempt < 1) {
            throw new IllegalArgumentException("question stage and attempt must be valid");
        }
        this.stage = stage;
        this.attempt = attempt;
        this.question = DomainText.require(question, "question");
        this.blocking = blocking;
        this.askedBy = DomainText.require(askedBy, "question asker", 128);
        this.askedAt = DomainText.requireTime(askedAt, "question asked time");
        this.answer = answer;
        this.answeredBy = answeredBy;
        this.answeredAt = answeredAt;
        validateAnswer();
    }

    public static HarnessQuestion ask(String questionId, HarnessStage stage, int attempt,
                                      String question, boolean blocking, String askedBy,
                                      Instant askedAt) {
        return new HarnessQuestion(questionId, stage, attempt, question, blocking,
                askedBy, askedAt, null, null, null);
    }

    public static HarnessQuestion restore(String questionId, HarnessStage stage, int attempt,
                                          String question, boolean blocking, String askedBy,
                                          Instant askedAt, String answer, String answeredBy,
                                          Instant answeredAt) {
        return new HarnessQuestion(questionId, stage, attempt, question, blocking,
                askedBy, askedAt, answer, answeredBy, answeredAt);
    }

    public boolean answer(String value, String actor, Instant now) {
        if (isAnswered()) {
            return false;
        }
        Instant answerTime = DomainText.requireTime(now, "question answered time");
        if (answerTime.isBefore(askedAt)) {
            throw new IllegalArgumentException("question answer time must not precede ask time");
        }
        answer = DomainText.require(value, "question answer");
        answeredBy = DomainText.require(actor, "question answerer", 128);
        answeredAt = answerTime;
        return true;
    }

    public boolean isAnswered() {
        return answeredAt != null;
    }

    boolean belongsTo(HarnessStage expectedStage, int expectedAttempt) {
        return stage == expectedStage && attempt == expectedAttempt;
    }

    boolean matchesRequest(HarnessStage expectedStage, int expectedAttempt,
                           String expectedQuestion, boolean expectedBlocking) {
        return belongsTo(expectedStage, expectedAttempt)
                && question.equals(DomainText.require(expectedQuestion, "question"))
                && blocking == expectedBlocking;
    }

    private void validateAnswer() {
        boolean complete = answer != null && answeredBy != null && answeredAt != null;
        boolean empty = answer == null && answeredBy == null && answeredAt == null;
        if (!complete && !empty) {
            throw new IllegalArgumentException("question answer fields must be all present or absent");
        }
        if (complete) {
            DomainText.require(answer, "restored question answer");
            DomainText.require(answeredBy, "restored question answerer", 128);
            if (answeredAt.isBefore(askedAt)) {
                throw new IllegalArgumentException("question answer time must not precede ask time");
            }
        }
    }
}
