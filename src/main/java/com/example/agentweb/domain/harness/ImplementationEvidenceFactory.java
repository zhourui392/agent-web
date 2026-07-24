package com.example.agentweb.domain.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 实现阶段 Git 基线/Changed Files 的确定性 Artifact 生成器。
 *
 * @author alex
 * @since 2026-07-23
 */
public final class ImplementationEvidenceFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ArtifactContent baseline(WorkspaceBaseline baseline) {
        if (baseline == null) {
            throw new IllegalArgumentException("implementation workspace baseline is required");
        }
        ObjectNode node = workspaceFields(baseline, baseline);
        node.put("baselineExplanation", "implementation attempt baseline captured before writes");
        writeFiles(node.putArray("baselineFiles"), baseline.getFiles());
        node.putArray("currentFiles");
        node.putArray("introducedFiles");
        node.putArray("files");
        return content(node);
    }

    public WorkspaceBaseline readBaseline(ArtifactContent content) {
        try {
            JsonNode node = MAPPER.readTree(content.copyBytes());
            return WorkspaceBaseline.capture(node.path("repositoryRoot").asText(),
                    node.path("branch").asText(), node.path("baselineHead").asText(),
                    node.path("baselineClean").asBoolean(false),
                    node.path("baselineDiffHash").asText(),
                    readFiles(node.path("baselineFiles")),
                    Instant.parse(node.path("baselineCapturedAt").asText()));
        } catch (Exception ex) {
            throw new IllegalArgumentException("implementation baseline artifact is invalid", ex);
        }
    }

    public RuntimeArtifactBundle enrich(RuntimeArtifactBundle bundle,
                                        WorkspaceChangeEvidence evidence) {
        bundle.requireStage(HarnessStage.IMPLEMENTATION);
        ObjectNode changed = workspaceFields(evidence.getBaseline(), evidence.getCurrent());
        writeFiles(changed.putArray("baselineFiles"), evidence.getBaselineFiles());
        writeFiles(changed.putArray("currentFiles"), evidence.getCurrentFiles());
        writeFiles(changed.putArray("introducedFiles"), evidence.getFiles());
        writeFiles(changed.putArray("files"), evidence.getFiles());
        RuntimeProducedArtifact replacement = new RuntimeProducedArtifact(
                "implementation-changed-files", ArtifactType.CHANGED_FILES,
                "application/json", ArtifactClassification.INTERNAL, content(changed));
        return bundle.replace(replacement);
    }

    private ObjectNode workspaceFields(WorkspaceBaseline baseline, WorkspaceBaseline current) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("repositoryRoot", baseline.getRepositoryRoot());
        node.put("branch", baseline.getBranch());
        node.put("baselineHead", baseline.getHead());
        node.put("currentHead", current.getHead());
        node.put("baselineClean", baseline.isClean());
        node.put("currentClean", current.isClean());
        node.put("baselineDiffHash", baseline.getDiffHash());
        node.put("diffHash", current.getDiffHash());
        node.put("baselineCapturedAt", baseline.getCapturedAt().toString());
        node.put("currentCapturedAt", current.getCapturedAt().toString());
        return node;
    }

    private void writeFiles(ArrayNode target, List<ChangedFileEvidence> files) {
        for (ChangedFileEvidence file : files) {
            ObjectNode item = target.addObject();
            item.put("path", file.getPath());
            item.put("status", file.getStatus());
            item.put("stateFingerprint", file.getStateFingerprint());
            item.put("sensitive", file.isSensitive());
        }
    }

    private List<ChangedFileEvidence> readFiles(JsonNode files) {
        if (files == null || files.isMissingNode() || files.isNull()) {
            return Collections.emptyList();
        }
        if (!files.isArray()) {
            throw new IllegalArgumentException("implementation baseline files are invalid");
        }
        List<ChangedFileEvidence> values = new ArrayList<ChangedFileEvidence>();
        for (JsonNode file : files) {
            values.add(new ChangedFileEvidence(file.path("path").asText(),
                    file.path("status").asText(), file.path("stateFingerprint").asText(),
                    file.path("sensitive").asBoolean(false)));
        }
        return values;
    }

    private ArtifactContent content(ObjectNode node) {
        try {
            return ArtifactContent.from(MAPPER.writeValueAsString(node)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("implementation evidence JSON could not be generated", ex);
        }
    }
}
