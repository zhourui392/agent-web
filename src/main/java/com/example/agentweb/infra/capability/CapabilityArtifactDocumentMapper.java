package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.CapabilityArtifactIntegrityException;
import com.example.agentweb.domain.capability.CapabilityKind;
import com.example.agentweb.domain.capability.CapabilityRequest;
import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.capability.McpCapability;
import com.example.agentweb.domain.capability.McpCapabilityType;
import com.example.agentweb.domain.capability.McpSecretReference;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.McpTransport;
import com.example.agentweb.domain.capability.SkillDependency;
import com.example.agentweb.domain.capability.SkillManifest;
import com.example.agentweb.domain.capability.SkillPackage;
import com.example.agentweb.domain.capability.SkillTrustSource;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Capability Artifact 的稳定 JSON 文档映射。
 *
 * @author alex
 * @since 2026-08-05
 */
final class CapabilityArtifactDocumentMapper {

    private final ObjectMapper objectMapper;

    CapabilityArtifactDocumentMapper(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("Capability Artifact ObjectMapper is required");
        }
        this.objectMapper = objectMapper;
    }

    SerializedDocument command(CommandDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Command Definition is required");
        }
        CommandDocument document = new CommandDocument();
        document.identifier = definition.getIdentifier();
        document.version = definition.getVersion();
        document.displayName = definition.getDisplayName();
        document.description = definition.getDescription();
        document.argumentHint = definition.getArgumentHint();
        document.promptTemplate = definition.getPromptTemplate();
        document.sourceDirectoryIdentifier = definition.getSourceDirectoryIdentifier();
        document.discoveredAt = definition.getDiscoveredAt().toEpochMilli();
        return serialize(document);
    }

    CommandDefinition command(String json) {
        CommandDocument document = deserialize(json, CommandDocument.class);
        return CommandDefinition.create(
                document.identifier, document.version, document.displayName,
                document.description, document.argumentHint, document.promptTemplate,
                document.sourceDirectoryIdentifier,
                Instant.ofEpochMilli(document.discoveredAt));
    }

    SerializedDocument skill(SkillPackage skillPackage) {
        if (skillPackage == null) {
            throw new IllegalArgumentException("Skill Package is required");
        }
        SkillDocument document = new SkillDocument();
        SkillManifest manifest = skillPackage.getManifest();
        document.id = manifest.getId();
        document.version = manifest.getVersion();
        document.description = manifest.getDescription();
        document.applicableUseCases = new ArrayList<String>(manifest.getApplicableUseCases());
        document.techTags = new ArrayList<String>(manifest.getTechTags());
        document.explicitTriggers = new ArrayList<String>(manifest.getExplicitTriggers());
        document.entryPath = manifest.getEntryPath();
        document.resourcePaths = new ArrayList<String>(manifest.getResourcePaths());
        document.dependencies = dependencyDocuments(manifest.getDependencies());
        document.conflicts = new ArrayList<String>(manifest.getConflicts());
        document.compatibleRuntimes = new ArrayList<String>(
                manifest.getCompatibleRuntimes());
        document.trustSource = manifest.getTrustSource().name();
        document.capabilityRequests = capabilityRequestDocuments(
                manifest.getCapabilityRequests());
        document.resourceHashes = new TreeMap<String, String>(
                skillPackage.getResourceHashes());
        return serialize(document);
    }

    SkillPackage skill(
            String json, String packageHash,
            ContentAddressedSkillArtifactStore.SkillArtifactContent content) {
        SkillDocument document = deserialize(json, SkillDocument.class);
        SkillManifest manifest = new SkillManifest(
                document.id, document.version, document.description,
                linkedSet(document.applicableUseCases), linkedSet(document.techTags),
                linkedSet(document.explicitTriggers), document.entryPath,
                linkedSet(document.resourcePaths), dependencies(document.dependencies),
                linkedSet(document.conflicts), linkedSet(document.compatibleRuntimes),
                SkillTrustSource.valueOf(document.trustSource),
                capabilityRequests(document.capabilityRequests));
        return new SkillPackage(manifest, packageHash, content.getEntryContent(),
                new LinkedHashMap<String, String>(document.resourceHashes),
                content.getResourceContents());
    }

    SkillManifest skillManifest(String json) {
        SkillDocument document = deserialize(json, SkillDocument.class);
        return new SkillManifest(
                document.id, document.version, document.description,
                linkedSet(document.applicableUseCases), linkedSet(document.techTags),
                linkedSet(document.explicitTriggers), document.entryPath,
                linkedSet(document.resourcePaths), dependencies(document.dependencies),
                linkedSet(document.conflicts), linkedSet(document.compatibleRuntimes),
                SkillTrustSource.valueOf(document.trustSource),
                capabilityRequests(document.capabilityRequests));
    }

    SerializedDocument mcp(McpServerDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("MCP Server Definition is required");
        }
        McpDocument document = new McpDocument();
        document.id = definition.getId();
        document.version = definition.getVersion();
        document.displayName = definition.getDisplayName();
        document.description = definition.getDescription();
        document.applicableUseCases = new ArrayList<String>(
                definition.getApplicableUseCases());
        document.compatibleRuntimes = new ArrayList<String>(
                definition.getCompatibleRuntimes());
        document.command = new ArrayList<String>(definition.getCommand());
        document.capabilities = mcpCapabilityDocuments(definition.getCapabilities());
        document.secretReferences = secretReferenceDocuments(
                definition.getSecretReferences());
        document.transport = definition.getTransport().name();
        document.workingDirectory = definition.getWorkingDirectory();
        document.endpoint = definition.getEndpoint();
        document.maximumAccess = definition.getMaximumAccess().name();
        document.startupTimeoutSeconds = definition.getStartupTimeoutSeconds();
        document.toolTimeoutSeconds = definition.getToolTimeoutSeconds();
        document.configurationHash = definition.getConfigurationHash();
        return serialize(document);
    }

    McpServerDefinition mcp(String json) {
        McpDocument document = deserialize(json, McpDocument.class);
        return McpServerDefinition.restore(
                document.id, document.version, document.displayName,
                document.description, linkedSet(document.applicableUseCases),
                linkedSet(document.compatibleRuntimes), document.command,
                mcpCapabilities(document.capabilities),
                secretReferences(document.secretReferences),
                McpTransport.valueOf(document.transport), document.workingDirectory,
                document.endpoint, CapabilityAccess.valueOf(document.maximumAccess),
                document.startupTimeoutSeconds, document.toolTimeoutSeconds,
                document.configurationHash);
    }

    private List<SkillDependencyDocument> dependencyDocuments(
            List<SkillDependency> dependencies) {
        List<SkillDependencyDocument> documents =
                new ArrayList<SkillDependencyDocument>();
        for (SkillDependency dependency : dependencies) {
            SkillDependencyDocument document = new SkillDependencyDocument();
            document.skillId = dependency.getSkillId();
            document.version = dependency.getVersion();
            documents.add(document);
        }
        return documents;
    }

    private List<SkillDependency> dependencies(
            List<SkillDependencyDocument> documents) {
        List<SkillDependency> dependencies = new ArrayList<SkillDependency>();
        for (SkillDependencyDocument document : required(documents)) {
            dependencies.add(new SkillDependency(document.skillId, document.version));
        }
        return dependencies;
    }

    private List<CapabilityRequestDocument> capabilityRequestDocuments(
            List<CapabilityRequest> requests) {
        List<CapabilityRequestDocument> documents =
                new ArrayList<CapabilityRequestDocument>();
        for (CapabilityRequest request : requests) {
            CapabilityRequestDocument document = new CapabilityRequestDocument();
            document.kind = request.getKind().name();
            document.access = request.getAccess().name();
            document.resource = request.getResource();
            documents.add(document);
        }
        return documents;
    }

    private List<CapabilityRequest> capabilityRequests(
            List<CapabilityRequestDocument> documents) {
        List<CapabilityRequest> requests = new ArrayList<CapabilityRequest>();
        for (CapabilityRequestDocument document : required(documents)) {
            requests.add(new CapabilityRequest(
                    CapabilityKind.valueOf(document.kind),
                    CapabilityAccess.valueOf(document.access), document.resource));
        }
        return requests;
    }

    private List<McpCapabilityDocument> mcpCapabilityDocuments(
            List<McpCapability> capabilities) {
        List<McpCapabilityDocument> documents =
                new ArrayList<McpCapabilityDocument>();
        for (McpCapability capability : capabilities) {
            McpCapabilityDocument document = new McpCapabilityDocument();
            document.id = capability.getId();
            document.type = capability.getType().name();
            document.access = capability.getAccess().name();
            documents.add(document);
        }
        return documents;
    }

    private List<McpCapability> mcpCapabilities(
            List<McpCapabilityDocument> documents) {
        List<McpCapability> capabilities = new ArrayList<McpCapability>();
        for (McpCapabilityDocument document : required(documents)) {
            capabilities.add(new McpCapability(document.id,
                    McpCapabilityType.valueOf(document.type),
                    CapabilityAccess.valueOf(document.access)));
        }
        return capabilities;
    }

    private List<SecretReferenceDocument> secretReferenceDocuments(
            List<McpSecretReference> references) {
        List<SecretReferenceDocument> documents =
                new ArrayList<SecretReferenceDocument>();
        for (McpSecretReference reference : references) {
            SecretReferenceDocument document = new SecretReferenceDocument();
            document.environmentVariable = reference.getEnvironmentVariable();
            document.reference = reference.getReference();
            documents.add(document);
        }
        return documents;
    }

    private List<McpSecretReference> secretReferences(
            List<SecretReferenceDocument> documents) {
        List<McpSecretReference> references = new ArrayList<McpSecretReference>();
        for (SecretReferenceDocument document : required(documents)) {
            references.add(new McpSecretReference(
                    document.environmentVariable, document.reference));
        }
        return references;
    }

    private LinkedHashSet<String> linkedSet(List<String> values) {
        if (values == null || values.contains(null)) {
            throw integrity("Capability Artifact document contains an invalid list");
        }
        return new LinkedHashSet<String>(values);
    }

    private <T> List<T> required(List<T> values) {
        if (values == null || values.contains(null)) {
            throw integrity("Capability Artifact document contains an invalid list");
        }
        return values;
    }

    private SerializedDocument serialize(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            return new SerializedDocument(json, CanonicalHashing.sha256(json));
        } catch (JsonProcessingException failure) {
            throw integrity("cannot serialize Capability Artifact", failure);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw integrity("cannot restore Capability Artifact", failure);
        }
    }

    private CapabilityArtifactIntegrityException integrity(String message) {
        return new CapabilityArtifactIntegrityException(
                "WORKBENCH_CAPABILITY_ARTIFACT_INTEGRITY_FAILED", message);
    }

    private CapabilityArtifactIntegrityException integrity(
            String message, Throwable cause) {
        return new CapabilityArtifactIntegrityException(
                "WORKBENCH_CAPABILITY_ARTIFACT_INTEGRITY_FAILED", message, cause);
    }

    static final class SerializedDocument {
        private final String json;
        private final String payloadHash;

        SerializedDocument(String json, String payloadHash) {
            this.json = json;
            this.payloadHash = payloadHash;
        }

        String getJson() {
            return json;
        }

        String getPayloadHash() {
            return payloadHash;
        }
    }

    public static final class CommandDocument {
        public String identifier;
        public String version;
        public String displayName;
        public String description;
        public String argumentHint;
        public String promptTemplate;
        public String sourceDirectoryIdentifier;
        public long discoveredAt;
    }

    public static final class SkillDocument {
        public String id;
        public String version;
        public String description;
        public List<String> applicableUseCases;
        public List<String> techTags;
        public List<String> explicitTriggers;
        public String entryPath;
        public List<String> resourcePaths;
        public List<SkillDependencyDocument> dependencies;
        public List<String> conflicts;
        public List<String> compatibleRuntimes;
        public String trustSource;
        public List<CapabilityRequestDocument> capabilityRequests;
        public Map<String, String> resourceHashes;
    }

    public static final class SkillDependencyDocument {
        public String skillId;
        public String version;
    }

    public static final class CapabilityRequestDocument {
        public String kind;
        public String access;
        public String resource;
    }

    public static final class McpDocument {
        public String id;
        public String version;
        public String displayName;
        public String description;
        public List<String> applicableUseCases;
        public List<String> compatibleRuntimes;
        public List<String> command;
        public List<McpCapabilityDocument> capabilities;
        public List<SecretReferenceDocument> secretReferences;
        public String transport;
        public String workingDirectory;
        public String endpoint;
        public String maximumAccess;
        public int startupTimeoutSeconds;
        public int toolTimeoutSeconds;
        public String configurationHash;
    }

    public static final class McpCapabilityDocument {
        public String id;
        public String type;
        public String access;
    }

    public static final class SecretReferenceDocument {
        public String environmentVariable;
        public String reference;
    }
}
