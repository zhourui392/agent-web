package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.CapabilityAccess;
import com.example.agentweb.domain.harness.CapabilityDecision;
import com.example.agentweb.domain.harness.CapabilityKind;
import com.example.agentweb.domain.harness.CapabilityRequest;
import com.example.agentweb.domain.harness.CapabilitySnapshot;
import com.example.agentweb.domain.harness.HarnessPromptPart;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.PromptPartType;
import com.example.agentweb.domain.harness.RejectedSkill;
import com.example.agentweb.domain.harness.SkillRejectionReason;
import com.example.agentweb.domain.harness.SkillSelectionReason;
import com.example.agentweb.domain.harness.SnapshotSkill;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Capability Snapshot JDBC 行与领域对象间的技术转换。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
final class CapabilitySnapshotJdbcCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CapabilitySnapshotJdbcCodec() {
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialize capability snapshot", ex);
        }
    }

    static CapabilitySnapshot read(ResultSet rs) throws SQLException {
        return CapabilitySnapshot.restore(
                rs.getString("run_id"), HarnessStage.valueOf(rs.getString("stage")),
                rs.getInt("attempt_number"), AgentRuntime.valueOf(rs.getString("runtime")),
                rs.getString("environment"), rs.getString("policy_version"),
                rs.getString("prompt_pack_id"), rs.getString("prompt_pack_version"),
                rs.getString("prompt_pack_hash"), readHashes(rs.getString("prompt_resource_hashes_json")),
                readSelectedSkills(rs.getString("selected_skills_json")),
                readRejectedSkills(rs.getString("rejected_skills_json")),
                readDecisions(rs.getString("capability_decisions_json")),
                readParts(rs.getString("prompt_parts_json")), rs.getString("final_prompt"),
                rs.getString("prompt_hash"), rs.getString("snapshot_hash"),
                Instant.ofEpochMilli(rs.getLong("created_at")));
    }

    private static Map<String, String> readHashes(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (JsonProcessingException ex) {
            throw corrupted("prompt resource hashes", ex);
        }
    }

    private static List<SnapshotSkill> readSelectedSkills(String json) {
        List<SnapshotSkill> result = new ArrayList<SnapshotSkill>();
        for (JsonNode node : array(json, "selected skills")) {
            result.add(new SnapshotSkill(text(node, "id"), text(node, "version"),
                    text(node, "packageHash"), SkillSelectionReason.valueOf(text(node, "reason"))));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<RejectedSkill> readRejectedSkills(String json) {
        List<RejectedSkill> result = new ArrayList<RejectedSkill>();
        for (JsonNode node : array(json, "rejected skills")) {
            result.add(new RejectedSkill(text(node, "skillId"), text(node, "version"),
                    SkillRejectionReason.valueOf(text(node, "reason"))));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<CapabilityDecision> readDecisions(String json) {
        List<CapabilityDecision> result = new ArrayList<CapabilityDecision>();
        for (JsonNode node : array(json, "capability decisions")) {
            JsonNode request = required(node, "request");
            CapabilityRequest capabilityRequest = new CapabilityRequest(
                    CapabilityKind.valueOf(text(request, "kind")),
                    CapabilityAccess.valueOf(text(request, "access")), text(request, "resource"));
            result.add(new CapabilityDecision(text(node, "skillId"), capabilityRequest,
                    node.path("authorized").asBoolean(), text(node, "reason")));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<HarnessPromptPart> readParts(String json) {
        List<HarnessPromptPart> result = new ArrayList<HarnessPromptPart>();
        for (JsonNode node : array(json, "prompt parts")) {
            result.add(new HarnessPromptPart(PromptPartType.valueOf(text(node, "type")),
                    text(node, "source"), text(node, "content"), text(node, "sha256")));
        }
        return Collections.unmodifiableList(result);
    }

    private static JsonNode array(String json, String name) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isArray()) {
                throw corrupted(name, null);
            }
            return node;
        } catch (JsonProcessingException ex) {
            throw corrupted(name, ex);
        }
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw corrupted("field " + field, null);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isTextual() || value.asText().trim().isEmpty()) {
            throw corrupted("field " + field, null);
        }
        return value.asText();
    }

    private static IllegalStateException corrupted(String name, Throwable cause) {
        return new IllegalStateException("corrupted capability snapshot " + name, cause);
    }
}
