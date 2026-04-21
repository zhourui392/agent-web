package com.example.agentweb.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BranchNameValidatorTest {

    @Test
    public void validPrefixes_withSlash_returnTrimmedValue() {
        assertEquals("feature/foo", BranchNameValidator.validateAndNormalize("feature/foo"));
        assertEquals("release/1.2", BranchNameValidator.validateAndNormalize("release/1.2"));
        assertEquals("hotfix/x", BranchNameValidator.validateAndNormalize("hotfix/x"));
        assertEquals("cr/abc", BranchNameValidator.validateAndNormalize("cr/abc"));
        assertEquals("bugfix/123", BranchNameValidator.validateAndNormalize("bugfix/123"));
    }

    @Test
    public void validPrefixes_withDash_returnTrimmedValue() {
        assertEquals("release-20260402-version",
                BranchNameValidator.validateAndNormalize("release-20260402-version"));
        assertEquals("feature-foo", BranchNameValidator.validateAndNormalize("feature-foo"));
        assertEquals("hotfix-x", BranchNameValidator.validateAndNormalize("hotfix-x"));
        assertEquals("cr-123", BranchNameValidator.validateAndNormalize("cr-123"));
        assertEquals("bugfix-abc", BranchNameValidator.validateAndNormalize("bugfix-abc"));
    }

    @Test
    public void validPrefixes_withoutSeparator_returnTrimmedValue() {
        // 放宽：关键字后可直接跟主体，不强制 '/' 或 '-'
        assertEquals("release20260402", BranchNameValidator.validateAndNormalize("release20260402"));
        assertEquals("featurefoo", BranchNameValidator.validateAndNormalize("featurefoo"));
        assertEquals("crabc", BranchNameValidator.validateAndNormalize("crabc"));
    }

    @Test
    public void leadingAndTrailingWhitespace_isStripped() {
        assertEquals("feature/foo", BranchNameValidator.validateAndNormalize("  feature/foo  "));
        assertEquals("feature/foo", BranchNameValidator.validateAndNormalize("\tfeature/foo\n"));
    }

    @Test
    public void null_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BranchNameValidator.validateAndNormalize(null));
        assertTrue(ex.getMessage().contains("不能为空"));
    }

    @Test
    public void blankOrEmpty_throws() {
        assertThrows(IllegalArgumentException.class, () -> BranchNameValidator.validateAndNormalize(""));
        assertThrows(IllegalArgumentException.class, () -> BranchNameValidator.validateAndNormalize("   "));
    }

    @Test
    public void unknownPrefix_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BranchNameValidator.validateAndNormalize("master"));
        assertTrue(ex.getMessage().contains("必须以"));
    }

    @Test
    public void keywordAloneWithoutBody_throws() {
        // 光一个关键字没有分支主体，仍然非法
        assertThrows(IllegalArgumentException.class, () -> BranchNameValidator.validateAndNormalize("feature"));
        assertThrows(IllegalArgumentException.class, () -> BranchNameValidator.validateAndNormalize("release"));
        assertThrows(IllegalArgumentException.class, () -> BranchNameValidator.validateAndNormalize("cr"));
    }

    @Test
    public void caseSensitive_uppercasePrefixRejected() {
        // 前缀小写是约定，避免同一分支两种大小写并存
        assertThrows(IllegalArgumentException.class, () -> BranchNameValidator.validateAndNormalize("Feature/foo"));
    }
}
