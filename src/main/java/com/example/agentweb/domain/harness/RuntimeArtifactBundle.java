package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次 Runtime 成功结果的结构化 Artifact Bundle。
 *
 * <p>Bundle 必须与执行时固化的阶段输出合同完全一致，既不能缺项，也不能通过
 * Agent 输出额外类型扩大写入范围。</p>
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class RuntimeArtifactBundle {

    public static final String SCHEMA_VERSION = "harness-artifact-bundle@1";

    private final String schemaVersion;
    private final HarnessStage stage;
    private final List<RuntimeProducedArtifact> artifacts;

    private RuntimeArtifactBundle(String schemaVersion, HarnessStage stage,
                                  List<RuntimeProducedArtifact> artifacts,
                                  Set<ArtifactType> requiredOutputs) {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported runtime artifact bundle schema");
        }
        if (stage == null || artifacts == null || artifacts.isEmpty()
                || requiredOutputs == null || requiredOutputs.isEmpty()) {
            throw new IllegalArgumentException("runtime artifact bundle contract is incomplete");
        }
        Set<String> artifactIds = new HashSet<String>();
        Set<ArtifactType> actualTypes = EnumSet.noneOf(ArtifactType.class);
        List<RuntimeProducedArtifact> copy = new ArrayList<RuntimeProducedArtifact>(artifacts.size());
        for (RuntimeProducedArtifact artifact : artifacts) {
            if (artifact == null || !artifactIds.add(artifact.getArtifactId())
                    || !actualTypes.add(artifact.getArtifactType())) {
                throw new IllegalArgumentException(
                        "runtime artifact bundle ids and types must be unique");
            }
            copy.add(artifact);
        }
        Set<ArtifactType> expected = EnumSet.copyOf(requiredOutputs);
        if (!actualTypes.equals(expected)) {
            throw new IllegalArgumentException(
                    "runtime artifact bundle does not match required stage outputs");
        }
        this.schemaVersion = schemaVersion;
        this.stage = stage;
        this.artifacts = Collections.unmodifiableList(copy);
    }

    public static RuntimeArtifactBundle create(String schemaVersion, HarnessStage stage,
                                               List<RuntimeProducedArtifact> artifacts,
                                               Set<ArtifactType> requiredOutputs) {
        return new RuntimeArtifactBundle(schemaVersion, stage, artifacts, requiredOutputs);
    }

    public void requireStage(HarnessStage expectedStage) {
        if (stage != expectedStage) {
            throw new IllegalHarnessTransitionException(
                    "runtime artifact bundle belongs to a different stage");
        }
    }

    public RuntimeProducedArtifact artifact(ArtifactType type) {
        if (type == null) {
            throw new IllegalArgumentException("runtime artifact type is required");
        }
        for (RuntimeProducedArtifact artifact : artifacts) {
            if (artifact.getArtifactType() == type) {
                return artifact;
            }
        }
        throw new IllegalArgumentException("runtime artifact type is not in bundle: " + type);
    }

    public RuntimeArtifactBundle replace(RuntimeProducedArtifact replacement) {
        if (replacement == null) {
            throw new IllegalArgumentException("replacement runtime artifact is required");
        }
        List<RuntimeProducedArtifact> replaced = new ArrayList<RuntimeProducedArtifact>();
        Set<ArtifactType> expected = EnumSet.noneOf(ArtifactType.class);
        boolean found = false;
        for (RuntimeProducedArtifact artifact : artifacts) {
            expected.add(artifact.getArtifactType());
            if (artifact.getArtifactType() == replacement.getArtifactType()) {
                replaced.add(replacement);
                found = true;
            } else {
                replaced.add(artifact);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("replacement artifact type is not in bundle");
        }
        return create(schemaVersion, stage, replaced, expected);
    }
}
