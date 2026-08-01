package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Workbench 单轮 Prompt 的不可变、确定性装配结果。
 *
 * <p>输入顺序不影响结果；每个类型最多一个，安全、范围、阶段规则、能力、
 * Workspace Context、Workbench 原始目标、用户输入和输出契约必须齐全。
 * Handoff、可信附件与当前阶段历史仅在存在真实来源时加入。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PreparedWorkbenchPrompt {

    private final List<WorkbenchPromptPart> parts;
    private final String finalPrompt;
    private final String promptHash;
    private final WorkbenchPromptHistoryDelivery historyDelivery;

    private PreparedWorkbenchPrompt(
            List<WorkbenchPromptPart> parts,
            WorkbenchPromptHistoryDelivery historyDelivery) {
        if (parts == null || parts.isEmpty() || parts.contains(null)) {
            throw new IllegalArgumentException(
                    "workbench prompt parts must not be empty or contain null");
        }
        if (historyDelivery == null) {
            throw new IllegalArgumentException(
                    "workbench prompt history delivery must not be null");
        }
        Map<WorkbenchPromptPartType, WorkbenchPromptPart> indexed =
                index(parts);
        requireComplete(indexed);
        this.parts = ordered(indexed);
        this.finalPrompt = assemble(this.parts);
        this.promptHash = CanonicalHashing.sha256(finalPrompt);
        this.historyDelivery = historyDelivery;
    }

    public static PreparedWorkbenchPrompt assemble(
            List<WorkbenchPromptPart> parts,
            WorkbenchPromptHistoryDelivery historyDelivery) {
        return new PreparedWorkbenchPrompt(parts, historyDelivery);
    }

    public WorkbenchRunPromptPayload freezePayload(
            String runId, Instant createdAt) {
        return WorkbenchRunPromptPayload.freeze(
                runId, finalPrompt, historyDelivery, createdAt);
    }

    public List<PromptPartSnapshot> snapshots() {
        List<PromptPartSnapshot> result =
                new ArrayList<PromptPartSnapshot>();
        for (WorkbenchPromptPart part : parts) {
            result.add(part.snapshot());
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<WorkbenchPromptPartType, WorkbenchPromptPart> index(
            List<WorkbenchPromptPart> parts) {
        Map<WorkbenchPromptPartType, WorkbenchPromptPart> indexed =
                new EnumMap<WorkbenchPromptPartType, WorkbenchPromptPart>(
                        WorkbenchPromptPartType.class);
        for (WorkbenchPromptPart part : parts) {
            if (indexed.put(part.getType(), part) != null) {
                throw new IllegalArgumentException(
                        "workbench prompt part type must be unique: "
                                + part.getType());
            }
        }
        return indexed;
    }

    private static void requireComplete(
            Map<WorkbenchPromptPartType, WorkbenchPromptPart> indexed) {
        for (WorkbenchPromptPartType type : WorkbenchPromptPartType.values()) {
            if (type.isRequired() && !indexed.containsKey(type)) {
                throw new IllegalArgumentException(
                        "workbench prompt is missing required part: " + type);
            }
        }
    }

    private static List<WorkbenchPromptPart> ordered(
            Map<WorkbenchPromptPartType, WorkbenchPromptPart> indexed) {
        List<WorkbenchPromptPart> result =
                new ArrayList<WorkbenchPromptPart>();
        for (WorkbenchPromptPartType type : WorkbenchPromptPartType.values()) {
            WorkbenchPromptPart part = indexed.get(type);
            if (part != null) {
                result.add(part);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static String assemble(List<WorkbenchPromptPart> parts) {
        StringBuilder prompt = new StringBuilder();
        for (WorkbenchPromptPart part : parts) {
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
