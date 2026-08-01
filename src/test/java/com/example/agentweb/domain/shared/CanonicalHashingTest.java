package com.example.agentweb.domain.shared;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 公共 canonical SHA-256 与 framing 契约。

 * @author alex
 * @since 2026-08-01
 */
class CanonicalHashingTest {

    @Test
    void hashesTextAndBytesWithTheSameStableSha256() {
        String expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

        assertEquals(expected, CanonicalHashing.sha256("abc"));
        assertEquals(expected,
                CanonicalHashing.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void framesNamesAndValuesByUtf16StringLengthCompatibly() {
        StringBuilder canonical = new StringBuilder();

        CanonicalHashing.appendFramed(canonical, "name", "value");
        CanonicalHashing.appendFramed(canonical, "empty", null);

        assertEquals("4:name=5:value\n5:empty=0:\n", canonical.toString());
    }

    @Test
    void rejectsNullHashContent() {
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalHashing.sha256((String) null));
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalHashing.sha256((byte[]) null));
    }
}
