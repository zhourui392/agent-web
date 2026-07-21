package com.example.agentweb.domain.issuelog;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link IssueLogEntry} 聚合根单测,覆盖 id 格式校验、必填字段、属性透出。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
public class IssueLogEntryTest {

    private final IssueLogDraft validDraft = new IssueLogDraft(
            "title",
            Arrays.asList("logic-pitfall"),
            Arrays.asList("svc"),
            "phen", "root", "sol", "");

    @Test
    public void constructor_valid_id_matching_i_xxx_pattern_should_succeed() {
        IssueLogEntry entry = new IssueLogEntry(
                "I-023",
                validDraft,
                "docs/issue-log/issue/I-023-x.md",
                Instant.parse("2026-05-19T09:00:00Z"));

        assertEquals("I-023", entry.getId());
        assertEquals("docs/issue-log/issue/I-023-x.md", entry.getFilePath());
        assertNotNull(entry.getCreatedAt());
    }

    @Test
    public void constructor_id_not_matching_i_number_format_should_throw() {
        assertThrows(IllegalArgumentException.class, () -> new IssueLogEntry(
                "X-001",
                validDraft,
                "docs/issue-log/issue/x.md",
                Instant.now()));
    }

    @Test
    public void constructor_id_is_null_should_throw() {
        assertThrows(IllegalArgumentException.class, () -> new IssueLogEntry(
                null,
                validDraft,
                "docs/issue-log/issue/x.md",
                Instant.now()));
    }

    @Test
    public void constructor_draft_is_null_should_throw() {
        assertThrows(IllegalArgumentException.class, () -> new IssueLogEntry(
                "I-001",
                null,
                "docs/issue-log/issue/x.md",
                Instant.now()));
    }

    @Test
    public void constructor_file_path_is_blank_should_throw() {
        assertThrows(IllegalArgumentException.class, () -> new IssueLogEntry(
                "I-001",
                validDraft,
                "   ",
                Instant.now()));
    }

    @Test
    public void constructor_id_allowed_more_than_three_digits_for_future_expansion() {
        // I-1024 这种 4 位也合法,避免硬卡 3 位
        IssueLogEntry entry = new IssueLogEntry(
                "I-1024",
                validDraft,
                "x.md",
                Instant.now());
        assertEquals("I-1024", entry.getId());
    }
}
