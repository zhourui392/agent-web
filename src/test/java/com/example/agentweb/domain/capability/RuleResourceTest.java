package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author alex
 * @since 2026-08-01
 */
class RuleResourceTest {

    @Test
    void shouldPreserveExactCatalogContentIncludingTrailingNewline() {
        String content = "rule body\n";

        RuleResource resource = new RuleResource(
                "rules", "rules.md", content, CanonicalHashing.sha256(content));

        assertEquals(content, resource.getContent());
    }
}
