package com.example.agentweb.app.agentrun;

import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public class KnowledgePreRecallContributorTest {

    @TempDir
    Path tempDir;

    @Test
    public void append_matchesIssueLogEntriesByKeywordsAndInjectsOnlyCandidatePaths() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Path issueLog = Files.createDirectories(root.resolve("docs/issue-log"));
        Path index = issueLog.resolve("INDEX.md");
        Files.write(index, Arrays.asList(
                "# Index",
                "- [I-021 risk danger disguised](issue/I-021-risk-danger.md)",
                "- [I-033 直退券无退款按钮](issue/I-033-refund-button.md)",
                "- [I-034 订单超时](issue/I-034-order-timeout.md)"
        ), StandardCharsets.UTF_8);
        WorkspaceContext context = new WorkspaceContext(
                root,
                root,
                null,
                null,
                Arrays.asList(new WorkspaceKnowledgeIndex("issue-log", index, "docs/issue-log/INDEX.md", 8)),
                java.util.Collections.emptyMap());
        AgentRunContext runContext = AgentRunContext.builder()
                .originalInput("用户说直退券没有退款按钮，订单 123")
                .runForm(RunForm.DIAGNOSE)
                .sourceDomain(SourceType.DIAGNOSE)
                .agentType(AgentType.CLAUDE)
                .workingDir(root.toString())
                .workspaceContext(context)
                .recallPolicy(workspaceKnowledgePolicy())
                .build();
        PromptAssembly assembly = new PromptAssembly(runContext);

        new KnowledgePreRecallContributor(null).append(assembly);

        Optional<PromptPart> part = assembly.parts().stream()
                .filter(p -> p.getType() == PromptPartType.KNOWLEDGE_PRE_RECALL)
                .findFirst();
        assertTrue(part.isPresent());
        assertTrue(part.get().getContent().contains("docs/issue-log/issue/I-033-refund-button.md"));
        assertFalse(part.get().getContent().contains("I-021-risk-danger"),
                "不相关条目不能仅因同在索引中被注入");
        assertFalse(part.get().getContent().contains("直退券无退款按钮"),
                "第一阶段只注入候选路径和提示，不复制索引正文");
        assertTrue(assembly.getWorkspaceKnowledgeHits().contains("docs/issue-log/issue/I-033-refund-button.md"));
    }

    @Test
    public void append_whenIndexMissing_shouldNotFailAndShouldNotAddPart() {
        Path missing = tempDir.resolve("missing/INDEX.md");
        WorkspaceContext context = new WorkspaceContext(
                tempDir,
                tempDir,
                null,
                null,
                Arrays.asList(new WorkspaceKnowledgeIndex("issue-log", missing,
                        "docs/issue-log/INDEX.md", 8)),
                java.util.Collections.emptyMap());
        AgentRunContext runContext = AgentRunContext.builder()
                .originalInput("任意问题")
                .runForm(RunForm.DIAGNOSE)
                .sourceDomain(SourceType.DIAGNOSE)
                .agentType(AgentType.CLAUDE)
                .workingDir(tempDir.toString())
                .workspaceContext(context)
                .recallPolicy(workspaceKnowledgePolicy())
                .build();
        PromptAssembly assembly = new PromptAssembly(runContext);

        new KnowledgePreRecallContributor(null).append(assembly);

        assertTrue(assembly.parts().isEmpty());
        assertTrue(assembly.getWorkspaceKnowledgeHits().isEmpty());
    }

    @Test
    public void append_stopwordOnlyOverlap_shouldNotHit() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Path index = writeIndex(root, Arrays.asList(
                "| I-034 | 订单支付超时 | 订单、支付、超时 | [I-034](issue/I-034-order-timeout.md) |"));
        PromptAssembly assembly = assemblyFor(root, index, "订单没到账，支付也失败了", 8,
                WorkspaceKnowledgeIndex.Mode.POINTER);

        new KnowledgePreRecallContributor(null).append(assembly);

        assertTrue(assembly.getWorkspaceKnowledgeHits().isEmpty(),
                "仅靠 订单/支付 这类 2 字泛词重叠不得命中(停用词治理)");
    }

    @Test
    public void append_twoWeakTokens_noHit_threeWeakTokens_hit() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Path index = writeIndex(root, Arrays.asList(
                "| I-040 | 红包翻倍规则 | 红包、翻倍 | [I-040](issue/I-040-a.md) |",
                "| I-041 | 红包翻倍夜间签到规则 | 红包、翻倍、签到、夜间 | [I-041](issue/I-041-b.md) |"));
        PromptAssembly weak = assemblyFor(root, index, "红包为什么翻倍", 8,
                WorkspaceKnowledgeIndex.Mode.POINTER);
        new KnowledgePreRecallContributor(null).append(weak);
        assertTrue(weak.getWorkspaceKnowledgeHits().isEmpty(),
                "2 个弱 token 重叠不得触发(收紧前是 2 即命中): " + weak.getWorkspaceKnowledgeHits());

        PromptAssembly strong = assemblyFor(root, index, "红包 翻倍 签到 都异常", 8,
                WorkspaceKnowledgeIndex.Mode.POINTER);
        new KnowledgePreRecallContributor(null).append(strong);
        assertTrue(strong.getWorkspaceKnowledgeHits().contains("docs/issue-log/issue/I-041-b.md"),
                "≥3 个不同弱 token 命中应触发: " + strong.getWorkspaceKnowledgeHits());
    }

    @Test
    public void append_indexTopKOverridesPolicyDefault() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Path index = writeIndex(root, Arrays.asList(
                "| I-050 | 风控A | 9200201 | [I-050](issue/I-050-a.md) |",
                "| I-051 | 风控B | 9200201 | [I-051](issue/I-051-b.md) |"));
        PromptAssembly assembly = assemblyFor(root, index, "结算报 9200201", 1,
                WorkspaceKnowledgeIndex.Mode.POINTER);

        new KnowledgePreRecallContributor(null).append(assembly);

        assertTrue(assembly.getWorkspaceKnowledgeHits().size() == 1,
                "index 显式 top_k=1 必须优先于 policy 缺省 8, 实际: "
                        + assembly.getWorkspaceKnowledgeHits());
    }

    @Test
    public void append_inlineModeInjectsMatchedLineTextAsFacts() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Path nav = Files.createDirectories(root.resolve("docs/navigation"));
        Path facts = nav.resolve("service-facts.md");
        Files.write(facts, Arrays.asList(
                "| 关键字 | appName | psaId |",
                "|---|---|---|",
                "| inventory-service | inventory-service | 660123 |",
                "| risk-service | risk-service | 660456 |"
        ), StandardCharsets.UTF_8);
        WorkspaceContext context = new WorkspaceContext(
                root, root, null, null,
                Arrays.asList(new WorkspaceKnowledgeIndex("service-facts", facts,
                        "docs/navigation/service-facts.md", 5, WorkspaceKnowledgeIndex.Mode.INLINE)),
                java.util.Collections.emptyMap());
        PromptAssembly assembly = assemblyWithContext(root, context, "inventory-service 库存扣减报错");

        new KnowledgePreRecallContributor(null).append(assembly);

        Optional<PromptPart> part = assembly.parts().stream()
                .filter(p -> p.getType() == PromptPartType.KNOWLEDGE_PRE_RECALL)
                .findFirst();
        assertTrue(part.isPresent(), "inline 索引命中应产出事实段");
        assertTrue(part.get().getContent().contains("| inventory-service | inventory-service | 660123 |"),
                "inline 模式应整行直注事实: " + part.get().getContent());
        assertFalse(part.get().getContent().contains("660456"),
                "未命中的事实行不得注入");
    }

    private Path writeIndex(Path root, java.util.List<String> rows) throws Exception {
        Path issueLog = Files.createDirectories(root.resolve("docs/issue-log"));
        Path index = issueLog.resolve("INDEX.md");
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("# Index");
        lines.addAll(rows);
        Files.write(index, lines, StandardCharsets.UTF_8);
        return index;
    }

    private PromptAssembly assemblyFor(Path root, Path index, String input, int topK,
                                       WorkspaceKnowledgeIndex.Mode mode) {
        WorkspaceContext context = new WorkspaceContext(
                root, root, null, null,
                Arrays.asList(new WorkspaceKnowledgeIndex("issue-log", index,
                        "docs/issue-log/INDEX.md", topK, mode)),
                java.util.Collections.emptyMap());
        return assemblyWithContext(root, context, input);
    }

    private PromptAssembly assemblyWithContext(Path root, WorkspaceContext context, String input) {
        AgentRunContext runContext = AgentRunContext.builder()
                .originalInput(input)
                .runForm(RunForm.DIAGNOSE)
                .sourceDomain(SourceType.DIAGNOSE)
                .agentType(AgentType.CLAUDE)
                .workingDir(root.toString())
                .workspaceContext(context)
                .recallPolicy(workspaceKnowledgePolicy())
                .build();
        return new PromptAssembly(runContext);
    }

    private RunRecallPolicy workspaceKnowledgePolicy() {
        return RunRecallPolicy.builder()
                .workspaceContextEnabled(true)
                .workspaceKnowledgeEnabled(true)
                .historicalRagEnabled(false)
                .historicalSourceFilter(SourceType.DIAGNOSE)
                .build();
    }
}
