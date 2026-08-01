package com.example.agentweb.domain.workbench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 阶段附加规则值对象的换行规范化、Unicode 长度与控制字符边界测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class AdditionalCapabilityRuleTest {

    private static final String ROCKET = new String(Character.toChars(0x1F680));

    @Test
    void givenMixedLineEndingsAndOuterWhitespaceWhenCreateThenNormalizeAndPreserveBody() {
        AdditionalCapabilityRule rule = AdditionalCapabilityRule.create(
                " \t\r\n第一行\r\n\t第二行\r第三行\n \t", 100);

        assertEquals("第一行\n\t第二行\n第三行", rule.getValue());
    }

    @Test
    void givenUnicodeSupplementaryCharactersWhenCreateThenCountCodePoints() {
        AdditionalCapabilityRule atLimit = AdditionalCapabilityRule.create(
                ROCKET + ROCKET, 2);

        assertEquals(ROCKET + ROCKET, atLimit.getValue());
        assertThrows(IllegalArgumentException.class,
                () -> AdditionalCapabilityRule.create(ROCKET + ROCKET + ROCKET, 2));
    }

    @Test
    void givenInvalidAndBoundaryMaximumWhenCreateThenEnforceOneToSixteenThousand() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AdditionalCapabilityRule.create("规则", 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AdditionalCapabilityRule.create("规则", 16001)),
                () -> assertEquals("规则",
                        AdditionalCapabilityRule.create("规则", 16000).getValue()));
    }

    @Test
    void givenLineFeedAndTabWhenCreateThenAllowBothCharacters() {
        AdditionalCapabilityRule rule = AdditionalCapabilityRule.create(
                "第一行\n\t第二行", 100);

        assertEquals("第一行\n\t第二行", rule.getValue());
    }

    @Test
    void givenOtherIsoControlsWhenCreateThenRejectThem() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AdditionalCapabilityRule.create(
                                "前" + ((char) 0x00) + "后", 100)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AdditionalCapabilityRule.create(
                                "前" + ((char) 0x0B) + "后", 100)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AdditionalCapabilityRule.create(
                                "前" + ((char) 0x7F) + "后", 100)));
    }

    @Test
    void givenBlankContentWhenCreateThenRejectInsteadOfRepresentingBlank() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AdditionalCapabilityRule.create(null, 100)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AdditionalCapabilityRule.create("", 100)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AdditionalCapabilityRule.create(" \t\r\n ", 100)));
    }
}
