package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.RejectedCapability;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.capability.ResolvedCommandBinding;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchRunAttachment;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.context.WorkbenchContextDocumentContentState;
import com.example.agentweb.domain.workbench.context.WorkbenchContextDocumentSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Workbench SQLite JSON 值对象 Codec；所有 Hash 和业务不变量仍由 Domain restore 校验。
 *
 * @author alex
 * @since 2026-08-01
 */
final class WorkbenchJsonCodec {

    private final ObjectMapper mapper = new ObjectMapper();

    String writeCapabilityBinding(ResolvedCapabilityBinding binding) {
        ObjectNode root = mapper.createObjectNode();
        root.put("policyVersion", binding.getPolicyVersion());
        root.put("profileId", binding.getProfileId());
        root.put("profileVersion", binding.getProfileVersion());
        root.put("profileHash", binding.getProfileHash());
        root.put("runtimeCompatibility", binding.getRuntimeCompatibility());
        root.put("bindingHash", binding.getBindingHash());
        ArrayNode rules = root.putArray("rules");
        for (ResolvedRuleBinding rule : binding.getRules()) {
            ObjectNode node = rules.addObject();
            node.put("id", rule.getId());
            node.put("version", rule.getVersion());
            node.put("source", rule.getSource());
            node.put("contentHash", rule.getContentHash());
            node.put("mandatory", rule.isMandatory());
            node.put("safeSummary", rule.getSafeSummary());
        }
        ArrayNode skills = root.putArray("skills");
        for (ResolvedSkillBinding skill : binding.getSkills()) {
            ObjectNode node = skills.addObject();
            node.put("id", skill.getId());
            node.put("version", skill.getVersion());
            node.put("source", skill.getSource());
            node.put("packageHash", skill.getPackageHash());
            node.put("trustTier", skill.getTrustTier());
        }
        ArrayNode mcpServers = root.putArray("mcpServers");
        for (ResolvedMcpServerBinding mcp : binding.getMcpServers()) {
            ObjectNode node = mcpServers.addObject();
            node.put("id", mcp.getId());
            node.put("version", mcp.getVersion());
            node.put("definitionHash", mcp.getDefinitionHash());
            node.put("access", mcp.getAccess().name());
            node.put("transport", mcp.getTransport());
        }
        ArrayNode rejected = root.putArray("rejected");
        for (RejectedCapability rejection : binding.getRejected()) {
            ObjectNode node = rejected.addObject();
            node.put("id", rejection.getId());
            node.put("reasonCode", rejection.getReasonCode());
        }
        return write(root);
    }

    ResolvedCapabilityBinding readCapabilityBinding(String json) {
        JsonNode root = object(json, "capability binding");
        List<ResolvedRuleBinding> rules = new ArrayList<ResolvedRuleBinding>();
        for (JsonNode node : childArray(root, "rules")) {
            requireObject(node, "resolved rule");
            rules.add(new ResolvedRuleBinding(
                    text(node, "id"), text(node, "version"), text(node, "source"),
                    text(node, "contentHash"), bool(node, "mandatory"),
                    text(node, "safeSummary")));
        }
        List<ResolvedSkillBinding> skills = new ArrayList<ResolvedSkillBinding>();
        for (JsonNode node : childArray(root, "skills")) {
            requireObject(node, "resolved skill");
            skills.add(new ResolvedSkillBinding(
                    text(node, "id"), text(node, "version"), text(node, "source"),
                    text(node, "packageHash"), text(node, "trustTier")));
        }
        List<ResolvedMcpServerBinding> mcpServers =
                new ArrayList<ResolvedMcpServerBinding>();
        for (JsonNode node : childArray(root, "mcpServers")) {
            requireObject(node, "resolved MCP server");
            mcpServers.add(new ResolvedMcpServerBinding(
                    text(node, "id"), text(node, "version"),
                    text(node, "definitionHash"),
                    CapabilityAccess.valueOf(text(node, "access")),
                    text(node, "transport")));
        }
        List<RejectedCapability> rejected = new ArrayList<RejectedCapability>();
        for (JsonNode node : childArray(root, "rejected")) {
            requireObject(node, "rejected capability");
            rejected.add(new RejectedCapability(
                    text(node, "id"), text(node, "reasonCode")));
        }
        return ResolvedCapabilityBinding.restore(
                text(root, "policyVersion"), text(root, "profileId"),
                text(root, "profileVersion"), text(root, "profileHash"),
                rules, skills, mcpServers, rejected,
                text(root, "runtimeCompatibility"), text(root, "bindingHash"));
    }

    String writeCommandBinding(ResolvedCommandBinding binding) {
        ObjectNode root = mapper.createObjectNode();
        root.put("identifier", binding.getIdentifier());
        root.put("version", binding.getVersion());
        root.put("contentHash", binding.getContentHash());
        root.put("expandedPrompt", binding.getExpandedPrompt());
        root.put("expandedPromptHash", binding.getExpandedPromptHash());
        return write(root);
    }

    ResolvedCommandBinding readCommandBinding(String json) {
        JsonNode root = object(json, "resolved Command binding");
        return ResolvedCommandBinding.restore(
                text(root, "identifier"), text(root, "version"),
                text(root, "contentHash"), text(root, "expandedPrompt"),
                text(root, "expandedPromptHash"));
    }

    String writeContextDocumentSnapshots(
            List<WorkbenchContextDocumentSnapshot> documents) {
        ArrayNode result = mapper.createArrayNode();
        for (WorkbenchContextDocumentSnapshot document : documents) {
            ObjectNode node = result.addObject();
            node.put("contextDocumentIdentifier",
                    document.getContextDocumentIdentifier());
            node.put("sourceStageInstanceIdentifier",
                    document.getSourceStageInstanceIdentifier());
            putNullable(node, "sourceRunIdentifier",
                    document.getSourceRunIdentifier());
            node.put("documentName", document.getDocumentName());
            node.put("briefDescription", document.getBriefDescription());
            node.put("repositoryKey",
                    document.getDocumentReference().getRepositoryKey());
            node.put("relativePath",
                    document.getDocumentReference().getRelativePath());
            node.put("publishedContentHash",
                    document.getPublishedContentHash());
            node.put("contentState", document.getContentState().name());
        }
        return write(result);
    }

    List<WorkbenchContextDocumentSnapshot> readContextDocumentSnapshots(
            String json) {
        List<WorkbenchContextDocumentSnapshot> result =
                new ArrayList<WorkbenchContextDocumentSnapshot>();
        for (JsonNode node : array(json, "Context Document snapshots")) {
            requireObject(node, "Context Document snapshot");
            result.add(new WorkbenchContextDocumentSnapshot(
                    text(node, "contextDocumentIdentifier"),
                    text(node, "sourceStageInstanceIdentifier"),
                    optionalText(node, "sourceRunIdentifier"),
                    text(node, "documentName"),
                    text(node, "briefDescription"),
                    DocumentReference.of(
                            text(node, "repositoryKey"),
                            text(node, "relativePath")),
                    text(node, "publishedContentHash"),
                    WorkbenchContextDocumentContentState.valueOf(
                            text(node, "contentState"))));
        }
        return result;
    }

    String writePromptParts(List<PromptPartSnapshot> promptParts) {
        ArrayNode result = mapper.createArrayNode();
        for (PromptPartSnapshot part : promptParts) {
            ObjectNode node = result.addObject();
            node.put("type", part.getType());
            node.put("source", part.getSource());
            node.put("contentHash", part.getContentHash());
            node.put("contentSize", part.getContentSize());
        }
        return write(result);
    }

    List<PromptPartSnapshot> readPromptParts(String json) {
        List<PromptPartSnapshot> result = new ArrayList<PromptPartSnapshot>();
        for (JsonNode node : array(json, "prompt parts")) {
            requireObject(node, "prompt part");
            result.add(PromptPartSnapshot.of(
                    text(node, "type"), text(node, "source"),
                    text(node, "contentHash"), integer(node, "contentSize")));
        }
        return result;
    }

    List<VerifiedWorkbenchRunAttachment> readVerifiedAttachments(
            String json) {
        List<VerifiedWorkbenchRunAttachment> result =
                new ArrayList<VerifiedWorkbenchRunAttachment>();
        for (JsonNode node : array(json, "verified attachments")) {
            requireObject(node, "verified attachment");
            if (!"REPOSITORY_DOCUMENT".equals(attachmentType(node))) {
                continue;
            }
            result.add(VerifiedWorkbenchRunAttachment.restore(
                    DocumentReference.of(
                            text(node, "repositoryKey"),
                            text(node, "relativePath")),
                    text(node, "contentVersion"),
                    text(node, "mediaType"), longValue(node, "size")));
        }
        return result;
    }

    String writeVerifiedStageAttachments(
            List<VerifiedWorkbenchRunAttachment> attachments,
            List<VerifiedWorkbenchStageUploadedConversationAttachment>
                    uploadedAttachments) {
        ArrayNode result = mapper.createArrayNode();
        for (VerifiedWorkbenchRunAttachment attachment : attachments) {
            ObjectNode node = result.addObject();
            node.put("type", "REPOSITORY_DOCUMENT");
            node.put("repositoryKey", attachment.getDocumentReference()
                    .getRepositoryKey());
            node.put("relativePath", attachment.getDocumentReference()
                    .getRelativePath());
            node.put("contentVersion", attachment.getContentVersion());
            node.put("mediaType", attachment.getMediaType());
            node.put("size", attachment.getSize());
        }
        for (VerifiedWorkbenchStageUploadedConversationAttachment attachment
                : uploadedAttachments) {
            WorkbenchStageUploadedAttachmentBinding binding =
                    attachment.getBinding();
            ObjectNode node = result.addObject();
            node.put("type", "UPLOADED_CONVERSATION");
            node.put("attachmentId", attachment.getAttachmentId());
            node.put("ownerId", binding.getOwner().getOwnerId());
            node.put("ownerName", binding.getOwner().getOwnerName());
            node.put("workbenchId", binding.getWorkbenchId().getValue());
            node.put("stageInstanceIdentifier",
                    binding.getStageInstanceIdentifier());
            node.put("conversationId", binding.getConversationId());
            node.put("conversationGeneration",
                    binding.getConversationGeneration());
            node.put("displayName", attachment.getDisplayName());
            node.put("mediaType", attachment.getMediaType());
            node.put("size", attachment.getSize());
            node.put("contentHash", attachment.getContentHash());
            node.put("storageKey", attachment.getStorageKey());
            node.put("runtimeFileName", attachment.getRuntimeFileName());
            node.put("expiresAt", attachment.getExpiresAt().toEpochMilli());
            node.put("attachmentVersion", attachment.getAttachmentVersion());
        }
        return write(result);
    }

    List<VerifiedWorkbenchStageUploadedConversationAttachment>
            readVerifiedStageUploadedAttachments(String json) {
        List<VerifiedWorkbenchStageUploadedConversationAttachment> result =
                new ArrayList<
                        VerifiedWorkbenchStageUploadedConversationAttachment>();
        for (JsonNode node : array(json, "verified Stage attachments")) {
            requireObject(node, "verified Stage attachment");
            if (!"UPLOADED_CONVERSATION".equals(attachmentType(node))) {
                continue;
            }
            result.add(
                    VerifiedWorkbenchStageUploadedConversationAttachment
                            .restore(
                                    text(node, "attachmentId"),
                                    new WorkbenchStageUploadedAttachmentBinding(
                                            OwnerReference.of(
                                                    text(node, "ownerId"),
                                                    text(node, "ownerName")),
                                            WorkbenchId.of(
                                                    text(node, "workbenchId")),
                                            text(node,
                                                    "stageInstanceIdentifier"),
                                            text(node, "conversationId"),
                                            integer(node,
                                                    "conversationGeneration")),
                                    text(node, "displayName"),
                                    text(node, "mediaType"),
                                    longValue(node, "size"),
                                    text(node, "contentHash"),
                                    text(node, "storageKey"),
                                    text(node, "runtimeFileName"),
                                    java.time.Instant.ofEpochMilli(
                                            longValue(node, "expiresAt")),
                                    longValue(node, "attachmentVersion")));
        }
        return result;
    }

    private String attachmentType(JsonNode node) {
        JsonNode type = node.get("type");
        if (type == null || type.isNull()) {
            return "REPOSITORY_DOCUMENT";
        }
        if (!type.isTextual()
                || !("REPOSITORY_DOCUMENT".equals(type.textValue())
                || "UPLOADED_CONVERSATION".equals(type.textValue()))) {
            throw new IllegalArgumentException(
                    "verified attachment type is invalid");
        }
        return type.textValue();
    }

    String writeRuntimeEnforcement(RuntimeEnforcementSnapshot runtime) {
        ObjectNode root = mapper.createObjectNode();
        root.put("runtime", runtime.getRuntime());
        root.put("runtimeVersion", runtime.getRuntimeVersion());
        root.put("repositoryScopeHash", runtime.getRepositoryScopeHash());
        root.put("primaryRepositoryKey", runtime.getPrimaryRepositoryKey());
        root.put("runMode", runtime.getRunMode().name());
        root.set("writableRepositoryKeys", stringArray(runtime.getWritableRepositoryKeys()));
        root.put("timeoutSeconds", runtime.getTimeoutSeconds());
        root.put("outputLimitBytes", runtime.getOutputLimitBytes());
        return write(root);
    }

    RuntimeEnforcementSnapshot readRuntimeEnforcement(String json) {
        JsonNode root = object(json, "runtime enforcement");
        RunMode runMode = RunMode.valueOf(text(root, "runMode"));
        List<String> writableRepositories = stringList(root, "writableRepositoryKeys");
        if (runMode.modifiesWorkspace()) {
            return RuntimeEnforcementSnapshot.modify(
                    text(root, "runtime"), text(root, "runtimeVersion"),
                    text(root, "repositoryScopeHash"),
                    text(root, "primaryRepositoryKey"), writableRepositories,
                    longValue(root, "timeoutSeconds"),
                    longValue(root, "outputLimitBytes"));
        }
        if (!writableRepositories.isEmpty()) {
            throw invalid("read-only runtime contains writable repositories", null);
        }
        return RuntimeEnforcementSnapshot.readOnly(
                text(root, "runtime"), text(root, "runtimeVersion"),
                text(root, "repositoryScopeHash"),
                text(root, "primaryRepositoryKey"),
                longValue(root, "timeoutSeconds"),
                longValue(root, "outputLimitBytes"));
    }

    private ArrayNode stringArray(Iterable<String> values) {
        List<String> ordered = new ArrayList<String>();
        for (String value : values) {
            ordered.add(value);
        }
        Collections.sort(ordered);
        ArrayNode result = mapper.createArrayNode();
        for (String value : ordered) {
            result.add(value);
        }
        return result;
    }

    private List<String> stringList(JsonNode parent, String name) {
        List<String> result = new ArrayList<String>();
        for (JsonNode node : childArray(parent, name)) {
            if (!node.isTextual()) {
                throw invalid(name + " must contain only strings", null);
            }
            result.add(node.textValue());
        }
        return result;
    }

    private JsonNode array(String json, String name) {
        JsonNode root = read(json);
        if (!root.isArray()) {
            throw invalid(name + " json must be an array", null);
        }
        return root;
    }

    private JsonNode object(String json, String name) {
        JsonNode root = read(json);
        if (!root.isObject()) {
            throw invalid(name + " json must be an object", null);
        }
        return root;
    }

    private JsonNode childArray(JsonNode parent, String name) {
        JsonNode child = parent.get(name);
        if (child == null || !child.isArray()) {
            throw invalid(name + " must be an array", null);
        }
        return child;
    }

    private void requireObject(JsonNode node, String name) {
        if (!node.isObject()) {
            throw invalid(name + " must be an object", null);
        }
    }

    private String text(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isTextual()) {
            throw invalid(name + " must be a string", null);
        }
        return value.textValue();
    }

    private String optionalText(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(name + " must be null or a string", null);
        }
        return value.textValue();
    }

    private boolean bool(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isBoolean()) {
            throw invalid(name + " must be a boolean", null);
        }
        return value.booleanValue();
    }

    private int integer(JsonNode parent, String name) {
        long value = longValue(parent, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid(name + " is outside the integer range", null);
        }
        return (int) value;
    }

    private long longValue(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isIntegralNumber()) {
            throw invalid(name + " must be an integer", null);
        }
        return value.longValue();
    }

    private JsonNode read(String json) {
        if (json == null) {
            throw invalid("json must not be null", null);
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null) {
                throw invalid("json must contain a value", null);
            }
            return root;
        } catch (JsonProcessingException ex) {
            throw invalid("invalid workbench json", ex);
        }
    }

    private String write(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("could not serialize workbench metadata", ex);
        }
    }

    private void putNullable(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private IllegalStateException invalid(String detail, Throwable cause) {
        return new IllegalStateException("invalid workbench persistence json: " + detail, cause);
    }
}
