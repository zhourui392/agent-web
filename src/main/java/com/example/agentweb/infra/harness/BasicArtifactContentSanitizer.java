package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactContentSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 保持 JSON 可解析的基础 Secret 脱敏器。
 *
 * @author alex
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class BasicArtifactContentSanitizer implements ArtifactContentSanitizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i).*(api[_-]?key|secret|token|password|private[_-]?key).*"
    );
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)(api[_-]?key|secret|token|password)([\\s:=]+)[A-Za-z0-9_./+=-]{8,}"
    );
    private static final Pattern JWT = Pattern.compile(
            "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"
    );
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"
    );

    @Override
    public ArtifactContent sanitize(String contentType, ArtifactContent content) {
        if (contentType == null || content == null) {
            throw new IllegalArgumentException("artifact content type and body are required");
        }
        String text = new String(content.copyBytes(), StandardCharsets.UTF_8);
        if ("application/json".equals(contentType)) {
            try {
                JsonNode root = MAPPER.readTree(text);
                if (root != null) {
                    sanitizeNode(root);
                    return ArtifactContent.from(MAPPER.writeValueAsBytes(root));
                }
            } catch (Exception ignored) {
                // 无效 JSON 仍做文本脱敏，后续 Schema Gate 会明确拒绝。
            }
        }
        return ArtifactContent.from(redact(text).getBytes(StandardCharsets.UTF_8));
    }

    private void sanitizeNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (value.isTextual()) {
                    object.put(field.getKey(), SECRET_FIELD.matcher(field.getKey()).matches()
                            ? "[REDACTED]" : redact(value.asText()));
                } else {
                    sanitizeNode(value);
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) {
                JsonNode value = array.get(index);
                if (value.isTextual()) {
                    array.set(index, MAPPER.getNodeFactory().textNode(redact(value.asText())));
                } else {
                    sanitizeNode(value);
                }
            }
        }
    }

    private String redact(String value) {
        String named = NAMED_SECRET.matcher(value).replaceAll("$1$2[REDACTED]");
        String jwt = JWT.matcher(named).replaceAll("[REDACTED_JWT]");
        return PRIVATE_KEY.matcher(jwt).replaceAll("[REDACTED_PRIVATE_KEY]");
    }
}
