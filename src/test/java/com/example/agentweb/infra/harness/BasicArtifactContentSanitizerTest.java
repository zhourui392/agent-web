package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.ArtifactContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Markdown/JSON Artifact 的 Secret 脱敏与 JSON 可解析性测试。
 *
 * @author alex
 * @since 2026-07-23
 */
class BasicArtifactContentSanitizerTest {

    @Test
    void shouldRedactTextAndPreserveJsonStructure() throws Exception {
        BasicArtifactContentSanitizer sanitizer = new BasicArtifactContentSanitizer();
        ArtifactContent markdown = sanitizer.sanitize("text/markdown", ArtifactContent.from(
                "token=super-secret-token\n# report".getBytes(StandardCharsets.UTF_8)));
        ArtifactContent json = sanitizer.sanitize("application/json", ArtifactContent.from(
                "{\"token\":\"super-secret-token\",\"nested\":{\"safe\":\"ok\"}}"
                        .getBytes(StandardCharsets.UTF_8)));

        String markdownText = new String(markdown.copyBytes(), StandardCharsets.UTF_8);
        String jsonText = new String(json.copyBytes(), StandardCharsets.UTF_8);
        JsonNode root = new ObjectMapper().readTree(jsonText);
        assertFalse(markdownText.contains("super-secret-token"));
        assertFalse(jsonText.contains("super-secret-token"));
        assertTrue(root.path("nested").path("safe").asText().equals("ok"));
    }
}
