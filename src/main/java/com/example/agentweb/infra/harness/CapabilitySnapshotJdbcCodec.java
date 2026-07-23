package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.CapabilityAccess;
import com.example.agentweb.domain.harness.CapabilityDecision;
import com.example.agentweb.domain.harness.CapabilityKind;
import com.example.agentweb.domain.harness.CapabilityRequest;
import com.example.agentweb.domain.harness.CapabilitySnapshot;
import com.example.agentweb.domain.harness.HarnessPromptPart;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.McpCapability;
import com.example.agentweb.domain.harness.McpCapabilityType;
import com.example.agentweb.domain.harness.McpRejectionReason;
import com.example.agentweb.domain.harness.McpSecretReference;
import com.example.agentweb.domain.harness.PromptPartType;
import com.example.agentweb.domain.harness.RejectedMcpServer;
import com.example.agentweb.domain.harness.RejectedSkill;
import com.example.agentweb.domain.harness.RuntimeEnforcementProfile;
import com.example.agentweb.domain.harness.SelectedMcpServer;
import com.example.agentweb.domain.harness.SkillRejectionReason;
import com.example.agentweb.domain.harness.SkillSelectionReason;
import com.example.agentweb.domain.harness.SnapshotSkill;
import com.example.agentweb.domain.harness.WorkspaceBoundaryKind;
import com.example.agentweb.domain.harness.WorkspaceRepoSkill;
import com.example.agentweb.domain.harness.WorkspaceRuntimeInventory;
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
    private static final String SELECTED_SKILLS = "selected skills";
    private static final String REJECTED_SKILLS = "rejected skills";
    private static final String CAPABILITY_DECISIONS = "capability decisions";
    private static final String PROMPT_PARTS = "prompt parts";
    private static final String SELECTED_MCP_SERVERS = "selected MCP servers";
    private static final String REJECTED_MCP_SERVERS = "rejected MCP servers";
    private static final String COMMAND_FIELD = "command";
    private static final String CAPABILITIES_FIELD = "capabilities";
    private static final String SECRET_REFERENCES_FIELD = "secretReferences";

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
        String schemaVersion = rs.getString("schema_version");
        String runId = rs.getString("run_id");
        HarnessStage stage = HarnessStage.valueOf(rs.getString("stage"));
        int attempt = rs.getInt("attempt_number");
        AgentRuntime runtime = AgentRuntime.valueOf(rs.getString("runtime"));
        String environment = rs.getString("environment");
        String policyVersion = rs.getString("policy_version");
        String promptPackId = rs.getString("prompt_pack_id");
        String promptPackVersion = rs.getString("prompt_pack_version");
        String promptPackHash = rs.getString("prompt_pack_hash");
        Map<String, String> promptResourceHashes = readHashes(
                rs.getString("prompt_resource_hashes_json"));
        List<SnapshotSkill> selectedSkills = readSelectedSkills(rs.getString("selected_skills_json"));
        List<RejectedSkill> rejectedSkills = readRejectedSkills(rs.getString("rejected_skills_json"));
        List<CapabilityDecision> decisions = readDecisions(rs.getString("capability_decisions_json"));
        List<HarnessPromptPart> parts = readParts(rs.getString("prompt_parts_json"));
        String finalPrompt = rs.getString("final_prompt");
        String promptHash = rs.getString("prompt_hash");
        String snapshotHash = rs.getString("snapshot_hash");
        Instant createdAt = Instant.ofEpochMilli(rs.getLong("created_at"));
        if (CapabilitySnapshot.SCHEMA_M2.equals(schemaVersion)) {
            return CapabilitySnapshot.restore(runId, stage, attempt, runtime, environment,
                    policyVersion, promptPackId, promptPackVersion, promptPackHash,
                    promptResourceHashes, selectedSkills, rejectedSkills, decisions, parts,
                    finalPrompt, promptHash, snapshotHash, createdAt);
        }
        if (CapabilitySnapshot.SCHEMA_M3.equals(schemaVersion)) {
            return CapabilitySnapshot.restoreM3(runId, stage, attempt, runtime, environment,
                    policyVersion, promptPackId, promptPackVersion, promptPackHash,
                    promptResourceHashes, selectedSkills, rejectedSkills, decisions,
                    readSelectedMcpServers(rs.getString("selected_mcp_servers_json"), false),
                    readRejectedMcpServers(rs.getString("rejected_mcp_servers_json")),
                    readEnforcement(rs.getString("runtime_enforcement_json"), false), parts,
                    finalPrompt, promptHash, snapshotHash, createdAt);
        }
        if (!CapabilitySnapshot.SCHEMA_M3_1.equals(schemaVersion)) {
            throw corrupted("schema version", null);
        }
        return CapabilitySnapshot.restoreM31(runId, stage, attempt, runtime, environment,
                policyVersion, promptPackId, promptPackVersion, promptPackHash,
                promptResourceHashes, selectedSkills, rejectedSkills, decisions,
                readSelectedMcpServers(rs.getString("selected_mcp_servers_json"), true),
                readRejectedMcpServers(rs.getString("rejected_mcp_servers_json")),
                readEnforcement(rs.getString("runtime_enforcement_json"), true),
                readWorkspaceInventory(rs.getString("workspace_runtime_inventory_json")), parts,
                finalPrompt, promptHash, snapshotHash, createdAt);
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
        for (JsonNode node : array(json, SELECTED_SKILLS)) {
            result.add(new SnapshotSkill(text(node, "id"), text(node, "version"),
                    text(node, "packageHash"), SkillSelectionReason.valueOf(text(node, "reason"))));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<RejectedSkill> readRejectedSkills(String json) {
        List<RejectedSkill> result = new ArrayList<RejectedSkill>();
        for (JsonNode node : array(json, REJECTED_SKILLS)) {
            result.add(new RejectedSkill(text(node, "skillId"), text(node, "version"),
                    SkillRejectionReason.valueOf(text(node, "reason"))));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<CapabilityDecision> readDecisions(String json) {
        List<CapabilityDecision> result = new ArrayList<CapabilityDecision>();
        for (JsonNode node : array(json, CAPABILITY_DECISIONS)) {
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
        for (JsonNode node : array(json, PROMPT_PARTS)) {
            result.add(new HarnessPromptPart(PromptPartType.valueOf(text(node, "type")),
                    text(node, "source"), text(node, "content"), text(node, "sha256")));
        }
        return Collections.unmodifiableList(result);
    }

    static List<SelectedMcpServer> readSelectedMcpServers(String json, String schemaVersion) {
        if (CapabilitySnapshot.SCHEMA_M3_1.equals(schemaVersion)) {
            return readSelectedMcpServers(json, true);
        }
        if (CapabilitySnapshot.SCHEMA_M2.equals(schemaVersion)
                || CapabilitySnapshot.SCHEMA_M3.equals(schemaVersion)) {
            return readSelectedMcpServers(json, false);
        }
        throw corrupted("schema version", null);
    }

    private static List<SelectedMcpServer> readSelectedMcpServers(String json, boolean m31) {
        List<SelectedMcpServer> result = new ArrayList<SelectedMcpServer>();
        for (JsonNode node : array(json, SELECTED_MCP_SERVERS)) {
            List<String> command = new ArrayList<String>();
            for (JsonNode item : required(node, COMMAND_FIELD)) {
                command.add(item.asText());
            }
            List<McpCapability> capabilities = new ArrayList<McpCapability>();
            for (JsonNode item : required(node, CAPABILITIES_FIELD)) {
                capabilities.add(new McpCapability(text(item, "id"),
                        McpCapabilityType.valueOf(text(item, "type")),
                        CapabilityAccess.valueOf(text(item, "access"))));
            }
            List<McpSecretReference> secretReferences = new ArrayList<McpSecretReference>();
            for (JsonNode item : required(node, SECRET_REFERENCES_FIELD)) {
                secretReferences.add(new McpSecretReference(text(item, "environmentVariable"),
                        text(item, "reference")));
            }
            if (m31) {
                result.add(new SelectedMcpServer(text(node, "id"), text(node, "version"), command,
                        capabilities, secretReferences, required(node, "required").asBoolean(),
                        strings(node, "enabledToolNames"), strings(node, "disabledToolNames"),
                        required(node, "startupTimeoutSeconds").asInt(),
                        required(node, "toolTimeoutSeconds").asInt(),
                        text(node, "configurationHash")));
            } else {
                result.add(new SelectedMcpServer(text(node, "id"), text(node, "version"), command,
                        capabilities, secretReferences, required(node, "timeoutSeconds").asInt(),
                        text(node, "configurationHash")));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static List<RejectedMcpServer> readRejectedMcpServers(String json) {
        List<RejectedMcpServer> result = new ArrayList<RejectedMcpServer>();
        for (JsonNode node : array(json, REJECTED_MCP_SERVERS)) {
            result.add(new RejectedMcpServer(text(node, "id"), text(node, "version"),
                    McpRejectionReason.valueOf(text(node, "reason"))));
        }
        return Collections.unmodifiableList(result);
    }

    private static RuntimeEnforcementProfile readEnforcement(String json, boolean m31) {
        if (json == null || json.trim().isEmpty()) {
            throw corrupted("runtime enforcement", null);
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (m31) {
                return new RuntimeEnforcementProfile(text(node, "profileVersion"),
                        text(node, "adapterVersion"), text(node, "runtimeVersion"),
                        text(node, "compatibilityMatrixVersion"), text(node, "sandboxMode"),
                        required(node, "singleRunOverridesEnforced").asBoolean(),
                        required(node, "toolAllowDenyEnforced").asBoolean(),
                        required(node, "userConfigIsolated").asBoolean(),
                        required(node, "projectConfigAbsent").asBoolean(),
                        required(node, "repoSkillIsolationEnforced").asBoolean(),
                        required(node, "processTreeCancellationEnforced").asBoolean());
            }
            return new RuntimeEnforcementProfile(text(node, "profileVersion"),
                    text(node, "runtimeVersion"), text(node, "sandboxMode"),
                    required(node, "toolAllowlistEnforced").asBoolean(),
                    required(node, "userConfigIsolated").asBoolean(),
                    required(node, "cancellationSupported").asBoolean());
        } catch (JsonProcessingException ex) {
            throw corrupted("runtime enforcement", ex);
        }
    }

    private static WorkspaceRuntimeInventory readWorkspaceInventory(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw corrupted("workspace runtime inventory", null);
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode repoSkillNodes = required(node, "repoSkills");
            List<WorkspaceRepoSkill> repoSkills =
                    new ArrayList<WorkspaceRepoSkill>(repoSkillNodes.size());
            for (JsonNode skill : repoSkillNodes) {
                repoSkills.add(new WorkspaceRepoSkill(text(skill, "id"),
                        text(skill, "relativeEntryPath"), text(skill, "entryHash")));
            }
            return new WorkspaceRuntimeInventory(
                    WorkspaceBoundaryKind.valueOf(text(node, "boundaryKind")),
                    required(node, "projectConfigAbsent").asBoolean(), repoSkills);
        } catch (JsonProcessingException ex) {
            throw corrupted("workspace runtime inventory", ex);
        }
    }

    private static List<String> strings(JsonNode node, String field) {
        List<String> result = new ArrayList<String>();
        for (JsonNode item : required(node, field)) {
            if (!item.isTextual() || item.asText().trim().isEmpty()) {
                throw corrupted("field " + field, null);
            }
            result.add(item.asText());
        }
        return result;
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
