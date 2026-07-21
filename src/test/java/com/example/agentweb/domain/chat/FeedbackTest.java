package com.example.agentweb.domain.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Feedback} 值对象的 getter / equals / hashCode / Jackson 反序列化覆盖。
 * 拉满 domain.* 门禁 80% 用,无业务规则。
 *
 * @author zhourui(V33215020)
 * @since 2026/05/26
 */
public class FeedbackTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    @Test
    void getters_returnConstructorArgs() {
        Instant t = Instant.parse("2026-05-26T10:00:00Z");
        Feedback fb = new Feedback(FeedbackRating.CORRECT, "looks good", t);

        assertEquals(FeedbackRating.CORRECT, fb.getRating());
        assertEquals("looks good", fb.getComment());
        assertEquals(t, fb.getUpdatedAt());
    }

    @Test
    void allFieldsNullable_allowsPartialFeedback() {
        // 只评分不写字
        Feedback ratingOnly = new Feedback(FeedbackRating.INCORRECT, null, Instant.now());
        assertNull(ratingOnly.getComment());
        // 只写字不评分
        Feedback commentOnly = new Feedback(null, "无法判定", Instant.now());
        assertNull(commentOnly.getRating());
        // 全空也被允许 (调用方决定语义)
        Feedback blank = new Feedback(null, null, null);
        assertNull(blank.getRating());
        assertNull(blank.getComment());
        assertNull(blank.getUpdatedAt());
    }

    @Test
    void equals_reflexive() {
        Feedback fb = new Feedback(FeedbackRating.CORRECT, "x", Instant.EPOCH);
        assertEquals(fb, fb);
    }

    @Test
    void equals_symmetricForSameValues() {
        Instant t = Instant.parse("2026-05-26T10:00:00Z");
        Feedback a = new Feedback(FeedbackRating.PARTIALLY_CORRECT, "ok", t);
        Feedback b = new Feedback(FeedbackRating.PARTIALLY_CORRECT, "ok", t);

        assertEquals(a, b);
        assertEquals(b, a);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEquals_whenRatingDiffers() {
        Instant t = Instant.EPOCH;
        assertNotEquals(new Feedback(FeedbackRating.CORRECT, "c", t),
                new Feedback(FeedbackRating.INCORRECT, "c", t));
    }

    @Test
    void notEquals_whenCommentDiffers() {
        Instant t = Instant.EPOCH;
        assertNotEquals(new Feedback(FeedbackRating.CORRECT, "a", t),
                new Feedback(FeedbackRating.CORRECT, "b", t));
    }

    @Test
    void notEquals_whenUpdatedAtDiffers() {
        assertNotEquals(new Feedback(FeedbackRating.CORRECT, "c", Instant.EPOCH),
                new Feedback(FeedbackRating.CORRECT, "c", Instant.parse("2026-05-26T10:00:00Z")));
    }

    @Test
    void notEquals_toNull() {
        Feedback fb = new Feedback(FeedbackRating.CORRECT, "c", Instant.EPOCH);
        assertFalse(fb.equals(null));
    }

    @Test
    void notEquals_toDifferentType() {
        Feedback fb = new Feedback(FeedbackRating.CORRECT, "c", Instant.EPOCH);
        assertFalse(fb.equals("not a feedback"));
    }

    @Test
    void allNullFieldsEqualEachOther() {
        Feedback a = new Feedback(null, null, null);
        Feedback b = new Feedback(null, null, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void jacksonRoundTrip_preservesAllFields() throws Exception {
        Feedback original = new Feedback(FeedbackRating.PARTIALLY_CORRECT, "需要更多上下文",
                Instant.parse("2026-05-26T01:02:03Z"));

        String json = MAPPER.writeValueAsString(original);
        assertNotNull(json);
        assertTrue(json.contains("PARTIALLY_CORRECT"));

        Feedback restored = MAPPER.readValue(json, Feedback.class);
        assertEquals(original, restored);
    }

    @Test
    void jacksonRoundTrip_withNullsAllowed() throws Exception {
        // 验证 @JsonCreator 接受所有字段为 null
        String json = "{\"rating\":null,\"comment\":null,\"updatedAt\":null}";
        Feedback restored = MAPPER.readValue(json, Feedback.class);
        assertNull(restored.getRating());
        assertNull(restored.getComment());
        assertNull(restored.getUpdatedAt());
    }

    @Test
    void feedbackRating_enumValuesCoverAllThreeBuckets() {
        // 同步覆盖 FeedbackRating enum,避免单 enum 类拖累 domain 覆盖率
        assertEquals(3, FeedbackRating.values().length);
        assertEquals(FeedbackRating.CORRECT, FeedbackRating.valueOf("CORRECT"));
        assertEquals(FeedbackRating.PARTIALLY_CORRECT, FeedbackRating.valueOf("PARTIALLY_CORRECT"));
        assertEquals(FeedbackRating.INCORRECT, FeedbackRating.valueOf("INCORRECT"));
    }
}
