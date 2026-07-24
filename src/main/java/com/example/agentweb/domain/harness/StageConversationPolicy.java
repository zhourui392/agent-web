package com.example.agentweb.domain.harness;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 阶段对话中用于代表 Agent 回复的主 Artifact 策略。
 *
 * @author alex
 * @since 2026-07-24
 */
public final class StageConversationPolicy {

    private static final Map<HarnessStage, ArtifactType> PRIMARY_ARTIFACTS;

    static {
        Map<HarnessStage, ArtifactType> values =
                new EnumMap<HarnessStage, ArtifactType>(HarnessStage.class);
        values.put(HarnessStage.ANALYSIS, ArtifactType.REQUIREMENT);
        values.put(HarnessStage.DESIGN, ArtifactType.SOLUTION);
        values.put(HarnessStage.IMPLEMENTATION, ArtifactType.IMPLEMENTATION_SUMMARY);
        values.put(HarnessStage.DEPLOYMENT, ArtifactType.FINAL_REPORT);
        PRIMARY_ARTIFACTS = Collections.unmodifiableMap(values);
    }

    private StageConversationPolicy() {
    }

    public static ArtifactType primaryArtifact(HarnessStage stage) {
        if (stage == null) {
            throw new IllegalArgumentException("stage must not be null");
        }
        ArtifactType type = PRIMARY_ARTIFACTS.get(stage);
        if (type == null) {
            throw new IllegalArgumentException("conversation artifact policy is missing: " + stage);
        }
        return type;
    }
}
