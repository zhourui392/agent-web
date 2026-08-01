package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Workbench 单轮 Prompt 固定顺序、唯一用户输入与 Snapshot 同源测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class PreparedWorkbenchPromptTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-01T08:00:00Z");

    @Test
    void shouldAssembleFixedOrderAndFreezeExactPayloadAndSnapshots() {
        List<WorkbenchPromptPart> unordered = Arrays.asList(
                part(WorkbenchPromptPartType.USER_INPUT, "owner", "请核实真实需求"),
                part(WorkbenchPromptPartType.OUTPUT_INSTRUCTION,
                        "workbench-event-contract", "输出结构化运行事件"),
                part(WorkbenchPromptPartType.REPOSITORY_SCOPE,
                        "workbench-scope", "primary=agent-web; readable=agent-web"),
                part(WorkbenchPromptPartType.PLATFORM_SAFETY,
                        "platform-policy", "不得越过仓库边界"),
                part(WorkbenchPromptPartType.PHASE_RULES,
                        "solution-design@1", "当前阶段只读"),
                part(WorkbenchPromptPartType.WORKSPACE_CONTEXT,
                        "workspace-manifest", "遵守仓内 AGENTS 约束"),
                part(WorkbenchPromptPartType.ENVIRONMENT_GUARDRAIL,
                        "local", "不得读取 Secret"),
                part(WorkbenchPromptPartType.SELECTED_CAPABILITIES,
                        "binding-1", "skills=code-search; mcp=repository-query"),
                part(WorkbenchPromptPartType.PHASE_HISTORY,
                        "phase-session-1", "user: 上一轮问题\nassistant: 上一轮结论"),
                part(WorkbenchPromptPartType.UPSTREAM_HANDOFF,
                        "requirement-analysis@3", "Summary: 已确认范围"));

        PreparedWorkbenchPrompt prepared = PreparedWorkbenchPrompt.assemble(
                unordered, WorkbenchPromptHistoryDelivery.PROMPT_PREFIX);

        assertEquals(Arrays.asList(
                        WorkbenchPromptPartType.PLATFORM_SAFETY,
                        WorkbenchPromptPartType.ENVIRONMENT_GUARDRAIL,
                        WorkbenchPromptPartType.REPOSITORY_SCOPE,
                        WorkbenchPromptPartType.PHASE_RULES,
                        WorkbenchPromptPartType.SELECTED_CAPABILITIES,
                        WorkbenchPromptPartType.UPSTREAM_HANDOFF,
                        WorkbenchPromptPartType.WORKSPACE_CONTEXT,
                        WorkbenchPromptPartType.PHASE_HISTORY,
                        WorkbenchPromptPartType.USER_INPUT,
                        WorkbenchPromptPartType.OUTPUT_INSTRUCTION),
                partTypes(prepared.getParts()));
        assertEquals(1, occurrences(
                prepared.getFinalPrompt(), "## USER_INPUT\n"));
        assertEquals(CanonicalHashing.sha256(prepared.getFinalPrompt()),
                prepared.getPromptHash());

        WorkbenchRunPromptPayload payload = prepared.freezePayload(
                "run-1", CREATED_AT);
        assertEquals("run-1", payload.getRunId());
        assertEquals(prepared.getFinalPrompt(), payload.getFinalPrompt());
        assertEquals(prepared.getPromptHash(), payload.getPromptHash());
        assertEquals(WorkbenchPromptHistoryDelivery.PROMPT_PREFIX,
                payload.getHistoryDelivery());
        assertEquals(CREATED_AT, payload.getCreatedAt());

        List<PromptPartSnapshot> snapshots = prepared.snapshots();
        assertEquals(prepared.getParts().size(), snapshots.size());
        for (int index = 0; index < snapshots.size(); index++) {
            WorkbenchPromptPart source = prepared.getParts().get(index);
            PromptPartSnapshot snapshot = snapshots.get(index);
            assertEquals(source.getType().name(), snapshot.getType());
            assertEquals(source.getSource(), snapshot.getSource());
            assertEquals(CanonicalHashing.sha256(source.getContent()),
                    snapshot.getContentHash());
            assertEquals(source.getContent().getBytes(StandardCharsets.UTF_8).length,
                    snapshot.getContentSize());
        }
    }

    @Test
    void shouldAllowFirstPhaseWithoutHandoffOrPreviousHistory() {
        PreparedWorkbenchPrompt prepared = PreparedWorkbenchPrompt.assemble(
                requiredParts(), WorkbenchPromptHistoryDelivery.PROMPT_PREFIX);

        assertEquals(Arrays.asList(
                        WorkbenchPromptPartType.PLATFORM_SAFETY,
                        WorkbenchPromptPartType.ENVIRONMENT_GUARDRAIL,
                        WorkbenchPromptPartType.REPOSITORY_SCOPE,
                        WorkbenchPromptPartType.PHASE_RULES,
                        WorkbenchPromptPartType.SELECTED_CAPABILITIES,
                        WorkbenchPromptPartType.WORKSPACE_CONTEXT,
                        WorkbenchPromptPartType.USER_INPUT,
                        WorkbenchPromptPartType.OUTPUT_INSTRUCTION),
                partTypes(prepared.getParts()));
    }

    @Test
    void shouldRejectDuplicateUserInputBeforeCreatingPrompt() {
        List<WorkbenchPromptPart> parts = new ArrayList<WorkbenchPromptPart>(
                requiredParts());
        parts.add(part(
                WorkbenchPromptPartType.USER_INPUT, "duplicate", "另一个问题"));

        assertThrows(IllegalArgumentException.class,
                () -> PreparedWorkbenchPrompt.assemble(
                        parts, WorkbenchPromptHistoryDelivery.PROMPT_PREFIX));
    }

    @Test
    void shouldRejectMissingRequiredSecurityOrUserPart() {
        List<WorkbenchPromptPart> withoutSafety = new ArrayList<WorkbenchPromptPart>(
                requiredParts());
        withoutSafety.remove(0);
        assertThrows(IllegalArgumentException.class,
                () -> PreparedWorkbenchPrompt.assemble(
                        withoutSafety,
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX));

        List<WorkbenchPromptPart> withoutUser = new ArrayList<WorkbenchPromptPart>(
                requiredParts());
        withoutUser.remove(6);
        assertThrows(IllegalArgumentException.class,
                () -> PreparedWorkbenchPrompt.assemble(
                        withoutUser,
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX));
    }

    @Test
    void shouldRejectBlankPartContentAndMismatchedRestoredHash() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchPromptPart.of(
                        WorkbenchPromptPartType.USER_INPUT,
                        "owner", "   "));
        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchPromptPart.restore(
                        WorkbenchPromptPartType.USER_INPUT,
                        "owner", "真实问题",
                        repeat('f')));
    }

    private List<WorkbenchPromptPart> requiredParts() {
        return Arrays.asList(
                part(WorkbenchPromptPartType.PLATFORM_SAFETY,
                        "platform", "安全规则"),
                part(WorkbenchPromptPartType.ENVIRONMENT_GUARDRAIL,
                        "local", "环境规则"),
                part(WorkbenchPromptPartType.REPOSITORY_SCOPE,
                        "scope", "仓库范围"),
                part(WorkbenchPromptPartType.PHASE_RULES,
                        "profile", "阶段规则"),
                part(WorkbenchPromptPartType.SELECTED_CAPABILITIES,
                        "binding", "已选能力"),
                part(WorkbenchPromptPartType.WORKSPACE_CONTEXT,
                        "workspace", "受控上下文"),
                part(WorkbenchPromptPartType.USER_INPUT,
                        "owner", "用户问题"),
                part(WorkbenchPromptPartType.OUTPUT_INSTRUCTION,
                        "contract", "输出要求"));
    }

    private WorkbenchPromptPart part(
            WorkbenchPromptPartType type, String source, String content) {
        return WorkbenchPromptPart.of(type, source, content);
    }

    private List<WorkbenchPromptPartType> partTypes(
            List<WorkbenchPromptPart> parts) {
        List<WorkbenchPromptPartType> types =
                new ArrayList<WorkbenchPromptPartType>();
        for (WorkbenchPromptPart part : parts) {
            types.add(part.getType());
        }
        return Collections.unmodifiableList(types);
    }

    private int occurrences(String content, String target) {
        int count = 0;
        int offset = 0;
        while ((offset = content.indexOf(target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }

    private String repeat(char value) {
        char[] characters = new char[64];
        Arrays.fill(characters, value);
        return new String(characters);
    }
}
