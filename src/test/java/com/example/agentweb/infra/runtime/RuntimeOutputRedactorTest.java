package com.example.agentweb.infra.runtime;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runtime 输出脱敏与 Evidence 行边界契约。

 * @author alex
 * @since 2026-08-01
 */
class RuntimeOutputRedactorTest {

    private final RuntimeOutputRedactor redactor = new RuntimeOutputRedactor();

    @Test
    void redactsAllExactSecretsWithoutMutatingSafeText() {
        String output = "token=secret-one, credential=secret-two, safe=value";

        String redacted = redactor.redactSecrets(
                output, Arrays.asList("secret-one", "secret-two"));

        assertEquals("token=[REDACTED], credential=[REDACTED], safe=value", redacted);
        assertFalse(redacted.contains("secret-one"));
        assertFalse(redacted.contains("secret-two"));
        assertEquals(output, redactor.redactSecrets(output, Collections.<String>emptyList()));
    }

    @Test
    void redactsLongerOverlappingSecretFirst() {
        String redacted = redactor.redactSecrets(
                "credential=abcdef", Arrays.asList("abc", "abcdef"));

        assertEquals("credential=[REDACTED]", redacted);
        assertThrows(IllegalArgumentException.class,
                () -> redactor.redactSecrets("value", Collections.singletonList("")));
    }

    @Test
    void boundsEvidenceLineWithoutAddingProviderText() {
        assertEquals("abcd", redactor.boundEvidenceLine("abcdef", 4));
        assertEquals("abc", redactor.boundEvidenceLine("abc", 4));
        assertThrows(IllegalArgumentException.class,
                () -> redactor.boundEvidenceLine("abc", -1));
    }

    @Test
    void shouldSanitizeAnsiAndControlCharactersWhenPreparingDisplayText() {
        // Given
        String output = "\u001B[31mfailed\u001B[0m\nnext\u0007\tvalue";

        // When
        String sanitized = redactor.sanitizeDisplayText(output);

        // Then
        assertEquals("failed\nnext\tvalue", sanitized);
    }
}
