package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workbench Stage 单轮 Prompt 的不可变装配结果。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class PreparedWorkbenchStagePrompt {

    private static final Set<WorkbenchPromptPartType> REQUIRED =
            Collections.unmodifiableSet(EnumSet.of(
                    WorkbenchPromptPartType.PLATFORM_SAFETY,
                    WorkbenchPromptPartType.ENVIRONMENT_GUARDRAIL,
                    WorkbenchPromptPartType.REPOSITORY_SCOPE,
                    WorkbenchPromptPartType.STAGE_DEFINITION,
                    WorkbenchPromptPartType.STAGE_RULES,
                    WorkbenchPromptPartType.SELECTED_CAPABILITIES,
                    WorkbenchPromptPartType.GLOBAL_CONTEXT,
                    WorkbenchPromptPartType.WORKSPACE_CONTEXT,
                    WorkbenchPromptPartType.ORIGINAL_GOAL,
                    WorkbenchPromptPartType.USER_INPUT,
                    WorkbenchPromptPartType.OUTPUT_INSTRUCTION));
    private final List<WorkbenchPromptPart> parts;
    private final String finalPrompt;
    private final String promptHash;
    private final WorkbenchPromptHistoryDelivery historyDelivery;

    private PreparedWorkbenchStagePrompt(
            List<WorkbenchPromptPart> sourceParts,
            WorkbenchPromptHistoryDelivery historyDelivery) {
        if (sourceParts == null || sourceParts.isEmpty()
                || sourceParts.contains(null) || historyDelivery == null) {
            throw new IllegalArgumentException(
                    "Workbench Stage Prompt facts are required");
        }
        Map<WorkbenchPromptPartType, WorkbenchPromptPart> indexed =
                index(sourceParts);
        requireComplete(indexed);
        this.parts = ordered(indexed);
        this.finalPrompt = assemble(parts);
        this.promptHash = CanonicalHashing.sha256(finalPrompt);
        this.historyDelivery = historyDelivery;
    }

    public static PreparedWorkbenchStagePrompt assemble(
            List<WorkbenchPromptPart> parts,
            WorkbenchPromptHistoryDelivery historyDelivery) {
        return new PreparedWorkbenchStagePrompt(parts, historyDelivery);
    }

    public WorkbenchRunPromptPayload freezePayload(
            String runId, Instant createdAt) {
        return WorkbenchRunPromptPayload.freeze(
                runId, finalPrompt, historyDelivery, createdAt);
    }

    public List<PromptPartSnapshot> snapshots() {
        List<PromptPartSnapshot> snapshots =
                new ArrayList<PromptPartSnapshot>();
        for (WorkbenchPromptPart part : parts) {
            snapshots.add(part.snapshot());
        }
        return Collections.unmodifiableList(snapshots);
    }

    private Map<WorkbenchPromptPartType, WorkbenchPromptPart> index(
            List<WorkbenchPromptPart> sourceParts) {
        Map<WorkbenchPromptPartType, WorkbenchPromptPart> indexed =
                new EnumMap<WorkbenchPromptPartType, WorkbenchPromptPart>(
                        WorkbenchPromptPartType.class);
        for (WorkbenchPromptPart part : sourceParts) {
            if (indexed.put(part.getType(), part) != null) {
                throw new IllegalArgumentException(
                        "Workbench Stage Prompt part type must be unique: "
                                + part.getType());
            }
        }
        return indexed;
    }

    private void requireComplete(
            Map<WorkbenchPromptPartType, WorkbenchPromptPart> indexed) {
        for (WorkbenchPromptPartType type : REQUIRED) {
            if (!indexed.containsKey(type)) {
                throw new IllegalArgumentException(
                        "Workbench Stage Prompt is missing required part: "
                                + type);
            }
        }
    }

    private List<WorkbenchPromptPart> ordered(
            Map<WorkbenchPromptPartType, WorkbenchPromptPart> indexed) {
        List<WorkbenchPromptPart> ordered =
                new ArrayList<WorkbenchPromptPart>();
        for (WorkbenchPromptPartType type
                : WorkbenchPromptPartType.values()) {
            WorkbenchPromptPart part = indexed.get(type);
            if (part != null) {
                ordered.add(part);
            }
        }
        return Collections.unmodifiableList(ordered);
    }

    private String assemble(List<WorkbenchPromptPart> orderedParts) {
        StringBuilder prompt = new StringBuilder();
        for (WorkbenchPromptPart part : orderedParts) {
            if (prompt.length() > 0) {
                prompt.append("\n\n");
            }
            prompt.append("## ")
                    .append(part.getType().name())
                    .append('\n')
                    .append(part.getContent());
        }
        return prompt.toString();
    }
}
