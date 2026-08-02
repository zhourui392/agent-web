package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.RejectedCapability;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.workbench.AdditionalCapabilityRule;
import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.CommitTarget;
import com.example.agentweb.domain.workbench.Decision;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.HighImpactOperationTarget;
import com.example.agentweb.domain.workbench.HighImpactOperationType;
import com.example.agentweb.domain.workbench.LocalDeployTarget;
import com.example.agentweb.domain.workbench.OpenQuestion;
import com.example.agentweb.domain.workbench.ProductionWriteTarget;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.PushTarget;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchRunAttachment;
import com.example.agentweb.domain.workbench.VerifiedUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.UploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Workbench SQLite JSON 值对象 Codec；所有 Hash 和业务不变量仍由 Domain restore 校验。
 *
 * @author alex
 * @since 2026-08-01
 */
final class WorkbenchJsonCodec {

    private final ObjectMapper mapper = new ObjectMapper();

    String writeDecisions(List<Decision> decisions) {
        ArrayNode result = mapper.createArrayNode();
        for (Decision decision : decisions) {
            ObjectNode node = result.addObject();
            node.put("text", decision.getText());
            putNullable(node, "rationale", decision.getRationale());
            node.put("status", decision.getStatus().name());
        }
        return write(result);
    }

    List<Decision> readDecisions(String json) {
        List<Decision> result = new ArrayList<Decision>();
        for (JsonNode node : array(json, "decisions")) {
            requireObject(node, "decision");
            String status = text(node, "status");
            if (!Decision.Status.CONFIRMED.name().equals(status)) {
                throw invalid("unsupported decision status: " + status, null);
            }
            result.add(Decision.confirmed(
                    text(node, "text"), optionalText(node, "rationale")));
        }
        return result;
    }

    String writeOpenQuestions(List<OpenQuestion> questions) {
        ArrayNode result = mapper.createArrayNode();
        for (OpenQuestion question : questions) {
            ObjectNode node = result.addObject();
            node.put("text", question.getText());
            putNullable(node, "ownerHint", question.getOwnerHint());
        }
        return write(result);
    }

    List<OpenQuestion> readOpenQuestions(String json) {
        List<OpenQuestion> result = new ArrayList<OpenQuestion>();
        for (JsonNode node : array(json, "open questions")) {
            requireObject(node, "open question");
            result.add(OpenQuestion.of(
                    text(node, "text"), optionalText(node, "ownerHint")));
        }
        return result;
    }

    String writeDocuments(List<DocumentReference> documents) {
        ArrayNode result = mapper.createArrayNode();
        for (DocumentReference document : documents) {
            ObjectNode node = result.addObject();
            node.put("repositoryKey", document.getRepositoryKey());
            node.put("relativePath", document.getRelativePath());
        }
        return write(result);
    }

    List<DocumentReference> readDocuments(String json) {
        return readDocuments(array(json, "documents"));
    }

    String writeRunReferences(List<WorkbenchRunReference> runs) {
        ArrayNode result = mapper.createArrayNode();
        for (WorkbenchRunReference run : runs) {
            ObjectNode node = result.addObject();
            node.put("runId", run.getRunId());
            node.put("workbenchId", run.getWorkbenchId().getValue());
            node.put("phase", run.getPhase().name());
            node.put("safeSummary", run.getSafeSummary());
        }
        return write(result);
    }

    List<WorkbenchRunReference> readRunReferences(String json) {
        List<WorkbenchRunReference> result = new ArrayList<WorkbenchRunReference>();
        for (JsonNode node : array(json, "run references")) {
            requireObject(node, "run reference");
            result.add(WorkbenchRunReference.of(
                    text(node, "runId"), WorkbenchId.of(text(node, "workbenchId")),
                    WorkbenchPhase.valueOf(text(node, "phase")),
                    text(node, "safeSummary")));
        }
        return result;
    }

    String writeCapabilityOverride(CapabilityOverride override) {
        ObjectNode root = mapper.createObjectNode();
        root.set("addedOptionalSkillIds", stringArray(override.getAddedOptionalSkillIds()));
        root.set("removedOptionalSkillIds", stringArray(override.getRemovedOptionalSkillIds()));
        root.set("selectedOptionalMcpIds", stringArray(override.getSelectedOptionalMcpIds()));
        root.put("explicitOptionalMcpSelection",
                override.hasExplicitOptionalMcpSelection());
        root.set("selectedOptionalRuleIds", stringArray(override.getSelectedOptionalRuleIds()));
        putNullable(root, "additionalRule",
                override.getAdditionalRule() == null
                        ? null : override.getAdditionalRule().getValue());
        return write(root);
    }

    CapabilityOverride readCapabilityOverride(String json) {
        JsonNode root = object(json, "capability override");
        String additionalRule = optionalText(root, "additionalRule");
        Set<String> selectedOptionalMcpIds =
                stringSet(root, "selectedOptionalMcpIds");
        boolean explicitOptionalMcpSelection =
                root.has("explicitOptionalMcpSelection")
                        ? bool(root, "explicitOptionalMcpSelection")
                        : !selectedOptionalMcpIds.isEmpty();
        return CapabilityOverride.restore(
                stringSet(root, "addedOptionalSkillIds"),
                stringSet(root, "removedOptionalSkillIds"),
                selectedOptionalMcpIds,
                explicitOptionalMcpSelection,
                stringSet(root, "selectedOptionalRuleIds"),
                additionalRule == null
                        ? null : AdditionalCapabilityRule.restore(additionalRule));
    }

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

    String writeVerifiedAttachments(
            List<VerifiedWorkbenchRunAttachment> attachments) {
        return writeVerifiedAttachments(
                attachments,
                java.util.Collections
                        .<VerifiedUploadedConversationAttachment>emptyList());
    }

    String writeVerifiedAttachments(
            List<VerifiedWorkbenchRunAttachment> attachments,
            List<VerifiedUploadedConversationAttachment> uploadedAttachments) {
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
        for (VerifiedUploadedConversationAttachment attachment
                : uploadedAttachments) {
            ObjectNode node = result.addObject();
            node.put("type", "UPLOADED_CONVERSATION");
            node.put("attachmentId", attachment.getAttachmentId());
            node.put("ownerId", attachment.getBinding().getOwner().getOwnerId());
            node.put("ownerName", attachment.getBinding().getOwner().getOwnerName());
            node.put("workbenchId",
                    attachment.getBinding().getWorkbenchId().getValue());
            node.put("phase", attachment.getBinding().getPhase().name());
            node.put("conversationId",
                    attachment.getBinding().getConversationId());
            node.put("conversationGeneration",
                    attachment.getBinding().getConversationGeneration());
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

    List<VerifiedUploadedConversationAttachment>
            readVerifiedUploadedAttachments(String json) {
        List<VerifiedUploadedConversationAttachment> result =
                new ArrayList<VerifiedUploadedConversationAttachment>();
        for (JsonNode node : array(json, "verified attachments")) {
            requireObject(node, "verified attachment");
            if (!"UPLOADED_CONVERSATION".equals(attachmentType(node))) {
                continue;
            }
            result.add(VerifiedUploadedConversationAttachment.restore(
                    text(node, "attachmentId"),
                    new UploadedAttachmentBinding(
                            OwnerReference.of(
                                    text(node, "ownerId"),
                                    text(node, "ownerName")),
                            WorkbenchId.of(text(node, "workbenchId")),
                            WorkbenchPhase.valueOf(text(node, "phase")),
                            text(node, "conversationId"),
                            integer(node, "conversationGeneration")),
                    text(node, "displayName"), text(node, "mediaType"),
                    longValue(node, "size"), text(node, "contentHash"),
                    text(node, "storageKey"), text(node, "runtimeFileName"),
                    java.time.Instant.ofEpochMilli(longValue(node, "expiresAt")),
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

    String writeOperationTarget(HighImpactOperationTarget target) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", target.getType().name());
        if (target instanceof CommitTarget) {
            CommitTarget commit = (CommitTarget) target;
            root.put("repositoryKey", commit.getRepositoryKey());
            root.put("branch", commit.getBranch());
            root.put("expectedHead", commit.getExpectedHead());
            root.put("expectedStateHash", commit.getExpectedStateHash());
            root.set("includedPaths", documentsArray(commit.getIncludedPaths()));
            root.put("messageHash", commit.getMessageHash());
            root.put("safeMessagePreview", commit.getSafeMessagePreview());
        } else if (target instanceof PushTarget) {
            PushTarget push = (PushTarget) target;
            root.put("repositoryKey", push.getRepositoryKey());
            root.put("remoteName", push.getRemoteName());
            root.put("localBranch", push.getLocalBranch());
            root.put("remoteRef", push.getRemoteRef());
            root.put("expectedLocalHead", push.getExpectedLocalHead());
        } else if (target instanceof LocalDeployTarget) {
            LocalDeployTarget deploy = (LocalDeployTarget) target;
            root.put("templateId", deploy.getTemplateId());
            root.put("templateVersion", deploy.getTemplateVersion());
            root.put("templateHash", deploy.getTemplateHash());
            root.set("repositoryTargets", stringArray(deploy.getRepositoryTargets()));
            root.put("environment", deploy.getEnvironment().name());
            root.put("expectedWorkspaceStateHash", deploy.getExpectedWorkspaceStateHash());
            root.put("rollbackSummary", deploy.getRollbackSummary());
        } else if (target instanceof ProductionWriteTarget) {
            ProductionWriteTarget production = (ProductionWriteTarget) target;
            root.put("environment", production.getEnvironment());
            root.put("resourceReference", production.getResourceReference());
            root.put("expectedProductionStateHash",
                    production.getExpectedProductionStateHash());
        } else {
            throw new IllegalArgumentException(
                    "unsupported high-impact target: " + target.getClass().getName());
        }
        return write(root);
    }

    HighImpactOperationTarget readOperationTarget(String json,
                                                  HighImpactOperationType storedType) {
        JsonNode root = object(json, "high-impact target");
        HighImpactOperationType encodedType = HighImpactOperationType.valueOf(
                text(root, "type"));
        if (encodedType != storedType) {
            throw invalid("operation target type does not match its column", null);
        }
        switch (encodedType) {
            case GIT_COMMIT:
                return CommitTarget.create(
                        text(root, "repositoryKey"), text(root, "branch"),
                        text(root, "expectedHead"), text(root, "expectedStateHash"),
                        readDocuments(childArray(root, "includedPaths")),
                        text(root, "messageHash"), text(root, "safeMessagePreview"));
            case GIT_PUSH:
                return PushTarget.create(
                        text(root, "repositoryKey"), text(root, "remoteName"),
                        text(root, "localBranch"), text(root, "remoteRef"),
                        text(root, "expectedLocalHead"));
            case LOCAL_DEPLOY:
                String environment = text(root, "environment");
                if (!LocalDeployTarget.Environment.LOCAL.name().equals(environment)) {
                    throw invalid("local deploy target has non-local environment", null);
                }
                return LocalDeployTarget.create(
                        text(root, "templateId"), text(root, "templateVersion"),
                        text(root, "templateHash"),
                        stringList(root, "repositoryTargets"),
                        text(root, "expectedWorkspaceStateHash"),
                        text(root, "rollbackSummary"));
            case PRODUCTION_WRITE:
                return ProductionWriteTarget.describe(
                        text(root, "environment"), text(root, "resourceReference"),
                        text(root, "expectedProductionStateHash"));
            default:
                throw invalid("unsupported operation target type: " + encodedType, null);
        }
    }

    private ArrayNode documentsArray(List<DocumentReference> documents) {
        ArrayNode result = mapper.createArrayNode();
        for (DocumentReference document : documents) {
            ObjectNode node = result.addObject();
            node.put("repositoryKey", document.getRepositoryKey());
            node.put("relativePath", document.getRelativePath());
        }
        return result;
    }

    private List<DocumentReference> readDocuments(JsonNode array) {
        List<DocumentReference> result = new ArrayList<DocumentReference>();
        for (JsonNode node : array) {
            requireObject(node, "document reference");
            result.add(DocumentReference.of(
                    text(node, "repositoryKey"), text(node, "relativePath")));
        }
        return result;
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

    private Set<String> stringSet(JsonNode parent, String name) {
        return new HashSet<String>(stringList(parent, name));
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
