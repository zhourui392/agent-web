package com.example.agentweb.domain.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link GitIdentity} 构造期不变量单测（零 Mock）。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
class GitIdentityTest {

    @Test
    void of_valid_should_trim_and_keep_values() {
        GitIdentity id = GitIdentity.of("  周锐 ", "  zhourui@example.com ");
        assertEquals("周锐", id.getName());
        assertEquals("zhourui@example.com", id.getEmail());
    }

    @Test
    void of_blank_name_should_throw() {
        assertThrows(IllegalArgumentException.class, () -> GitIdentity.of("  ", "a@b.com"));
        assertThrows(IllegalArgumentException.class, () -> GitIdentity.of(null, "a@b.com"));
    }

    @Test
    void of_blank_email_should_throw() {
        assertThrows(IllegalArgumentException.class, () -> GitIdentity.of("name", " "));
        assertThrows(IllegalArgumentException.class, () -> GitIdentity.of("name", null));
    }

    @Test
    void of_malformed_email_should_throw() {
        assertThrows(IllegalArgumentException.class, () -> GitIdentity.of("name", "not-an-email"));
        assertThrows(IllegalArgumentException.class, () -> GitIdentity.of("name", "a@b"));
        assertThrows(IllegalArgumentException.class, () -> GitIdentity.of("name", "a b@c.com"));
    }

    @Test
    void equality_should_be_value_based() {
        assertEquals(GitIdentity.of("n", "a@b.com"), GitIdentity.of("n", "a@b.com"));
        assertEquals(GitIdentity.of("n", "a@b.com").hashCode(), GitIdentity.of("n", "a@b.com").hashCode());
    }
}
