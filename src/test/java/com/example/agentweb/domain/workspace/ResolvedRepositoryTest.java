package com.example.agentweb.domain.workspace;

import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author alex
 * @since 2026-08-01
 */
class ResolvedRepositoryTest {

    @Test
    void createsFromInfrastructureVerifiedFactsWithNormalizedRepositoryKey() {
        ResolvedRepository repository = ResolvedRepository.fromVerifiedFacts(
                "service-a\\", "/workspace/service-a/",
                fingerprint("service-a"), false);

        assertEquals("service-a", repository.getRepositoryKey());
        assertEquals("service-a", repository.getRelativePath());
        assertEquals("/workspace/service-a", repository.getRepositoryRoot());
        assertEquals(fingerprint("service-a"), repository.getRootFingerprint());
        assertFalse(repository.isEntrySymbolicLink());
    }

    @Test
    void rejectsRepositoryEntryKnownToBeSymbolicLink() {
        assertThrows(IllegalArgumentException.class,
                () -> ResolvedRepository.fromVerifiedFacts(
                        "service-a", "/workspace/service-a",
                        fingerprint("service-a"), true));
    }

    @Test
    void rejectsNonAbsoluteOrLexicallyUnnormalizedRealRoot() {
        assertThrows(IllegalArgumentException.class,
                () -> ResolvedRepository.fromVerifiedFacts(
                        "service-a", "workspace/service-a",
                        fingerprint("service-a"), false));
        assertThrows(IllegalArgumentException.class,
                () -> ResolvedRepository.fromVerifiedFacts(
                        "service-a", "/workspace/other/../service-a",
                        fingerprint("service-a"), false));
    }

    @Test
    void rejectsInvalidRootFingerprint() {
        assertThrows(IllegalArgumentException.class,
                () -> ResolvedRepository.fromVerifiedFacts(
                        "service-a", "/workspace/service-a", "not-a-sha256", false));
    }

    private static String fingerprint(String value) {
        return CanonicalHashing.sha256(value);
    }
}
