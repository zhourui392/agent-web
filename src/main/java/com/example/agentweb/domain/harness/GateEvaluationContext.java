package com.example.agentweb.domain.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 Gate 计算所需的阶段合同和当前 Artifact 基线。
 *
 * @author alex
 * @since 2026-07-23
 */
public final class GateEvaluationContext {

    private final HarnessStage stage;
    private final StageContract contract;
    private final Map<ArtifactType, GateArtifact> artifacts;

    public GateEvaluationContext(HarnessStage stage, StageContract contract,
                                 List<GateArtifact> artifacts) {
        if (stage == null || contract == null || contract.getStage() != stage) {
            throw new IllegalArgumentException("gate stage and contract must match");
        }
        if (artifacts == null || artifacts.contains(null)) {
            throw new IllegalArgumentException("gate artifacts must not contain null");
        }
        this.stage = stage;
        this.contract = contract;
        Map<ArtifactType, GateArtifact> latest = new EnumMap<ArtifactType, GateArtifact>(ArtifactType.class);
        for (GateArtifact artifact : artifacts) {
            GateArtifact previous = latest.get(artifact.getArtifactType());
            if (previous == null || artifact.getDescriptor().getVersion()
                    > previous.getDescriptor().getVersion()) {
                latest.put(artifact.getArtifactType(), artifact);
            }
        }
        this.artifacts = Collections.unmodifiableMap(latest);
    }

    public HarnessStage getStage() {
        return stage;
    }

    public StageContract getContract() {
        return contract;
    }

    public GateArtifact artifact(ArtifactType type) {
        return artifacts.get(type);
    }

    public List<GateArtifact> artifacts() {
        return Collections.unmodifiableList(new ArrayList<GateArtifact>(artifacts.values()));
    }

    public boolean hasAllRequiredOutputs() {
        return artifacts.keySet().containsAll(contract.getRequiredOutputArtifacts());
    }
}
