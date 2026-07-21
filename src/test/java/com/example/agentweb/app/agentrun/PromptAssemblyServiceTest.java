package com.example.agentweb.app.agentrun;

import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.EnvProperties;
import com.example.agentweb.infra.FsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断历史 RAG 召回已随诊断子系统摘除，historical 通道恒 disabled/notApplicable；
 * 本类只覆盖幸存的装配管线：env / workspace context / workspace knowledge / user input / output 顺序与门禁。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public class PromptAssemblyServiceTest {

    @TempDir
    Path tempDir;

    @Test
    public void assemble_shouldUseUserInputWhenHistoricalRagDoesNotInjectQuery() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        PromptAssemblyService service = service(envProperties("test", "[环境约束: 测试]\n"), root);
        AgentRunContext context = AgentRunContext.builder()
                .originalInput("普通诊断问题")
                .runForm(RunForm.DIAGNOSE)
                .sourceDomain(SourceType.DIAGNOSE)
                .agentType(AgentType.CODEX)
                .workingDir(root.toString())
                .env("test")
                .recallPolicy(diagnosePolicy())
                .build();

        PromptAssemblyResult result = service.assemble(context);

        assertTrue(result.getPrompt().contains("[USER_INPUT]\n普通诊断问题"));
        assertFalse(result.getPrompt().contains("[当前问题]"));
        assertNull(result.getHistoricalRagUsed());
        assertEquals("env", result.getGuardrailSource());
    }

    @Test
    public void assemble_shouldProduceStableHashForSamePromptAndDifferentHashWhenPromptChanges() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        PromptAssemblyService service = service(new EnvProperties(), root);
        AgentRunContext first = baseContext(root, "问题 A");
        AgentRunContext same = baseContext(root, "问题 A");
        AgentRunContext second = baseContext(root, "问题 B");

        PromptAssemblyResult r1 = service.assemble(first);
        PromptAssemblyResult r2 = service.assemble(same);
        PromptAssemblyResult r3 = service.assemble(second);

        assertEquals(r1.getPromptHash(), r2.getPromptHash());
        assertNotEquals(r1.getPromptHash(), r3.getPromptHash());
    }

    @Test
    public void assemble_shouldAppendBusinessProvidedOutputInstructionAfterUserInput() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        PromptAssemblyService service = service(new EnvProperties(), root);
        AgentRunContext context = AgentRunContext.builder()
                .originalInput("问题正文")
                .runForm(RunForm.DIAGNOSE)
                .sourceDomain(SourceType.DIAGNOSE)
                .agentType(AgentType.CLAUDE)
                .workingDir(root.toString())
                .outputInstruction("[输出格式要求] 末尾追加 [CONCLUSION]: 一句话结论")
                .recallPolicy(RunRecallPolicy.disabled())
                .build();

        PromptAssemblyResult result = service.assemble(context);

        assertTrue(result.getPrompt().contains("[USER_INPUT]\n问题正文"));
        assertTrue(result.getPrompt().contains("[输出格式要求] 末尾追加 [CONCLUSION]: 一句话结论"));
        assertTrue(result.getPrompt().indexOf("[USER_INPUT]")
                < result.getPrompt().indexOf("[输出格式要求]"));
    }

    @Test
    public void assemble_shouldHonorRunRecallPolicy_When_RecallDisabled() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Files.createDirectories(root.resolve("docs/issue-log"));
        Files.write(root.resolve("docs/issue-log/INDEX.md"),
                "- [I-033 直退券无退款按钮](issue/I-033-refund-button.md)\n".getBytes(StandardCharsets.UTF_8));
        PromptAssemblyService service = service(new EnvProperties(), root);
        AgentRunContext context = AgentRunContext.builder()
                .originalInput("直退券没有退款按钮")
                .runForm(RunForm.DIAGNOSE)
                .sourceDomain(SourceType.DIAGNOSE)
                .agentType(AgentType.CLAUDE)
                .workingDir(root.toString())
                .recallPolicy(RunRecallPolicy.disabled())
                .build();

        PromptAssemblyResult result = service.assemble(context);

        assertFalse(result.getPrompt().contains("[候选知识]"));
        assertFalse(result.getPrompt().contains("[相似历史诊断]"));
        assertTrue(result.getPrompt().contains("[USER_INPUT]\n直退券没有退款按钮"));
        assertTrue(result.getRecallContributions().stream()
                .anyMatch(c -> c.getChannel() == RecallChannel.WORKSPACE_KNOWLEDGE
                        && !c.isEnabled()));
        assertTrue(result.getRecallContributions().stream()
                .anyMatch(c -> c.getChannel() == RecallChannel.HISTORICAL_RAG
                        && !c.isEnabled()));
    }

    @Test
    public void assemble_shouldSkipWorkspaceContextAndKnowledge_When_PolicyDisablesWorkspaceContext()
            throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Files.createDirectories(root.resolve("docs/issue-log"));
        Files.write(root.resolve("docs/issue-log/INDEX.md"),
                "- [I-033 直退券无退款按钮](issue/I-033-refund-button.md)\n".getBytes(StandardCharsets.UTF_8));
        PromptAssemblyService service = service(new EnvProperties(), root);
        RunRecallPolicy policy = RunRecallPolicy.builder()
                .workspaceContextEnabled(false)
                .workspaceKnowledgeEnabled(false)
                .historicalRagEnabled(false)
                .build();
        AgentRunContext context = AgentRunContext.builder()
                .originalInput("直退券没有退款按钮")
                .runForm(RunForm.DIAGNOSE)
                .sourceDomain(SourceType.DIAGNOSE)
                .agentType(AgentType.CLAUDE)
                .workingDir(root.toString())
                .recallPolicy(policy)
                .build();

        PromptAssemblyResult result = service.assemble(context);

        assertFalse(result.getPrompt().contains("[Workspace Context]"));
        assertFalse(result.getPrompt().contains("[候选知识]"));
        assertTrue(result.getWorkspaceContextDocs().isEmpty());
        assertTrue(result.getWorkspaceKnowledgeHits().isEmpty());
    }

    @Test
    public void assemble_shouldStillRunWorkspaceKnowledge_When_ContextSummaryDisabled() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Files.createDirectories(root.resolve("docs/issue-log"));
        Files.write(root.resolve("docs/issue-log/INDEX.md"),
                "- [I-033 直退券无退款按钮](issue/I-033-refund-button.md)\n".getBytes(StandardCharsets.UTF_8));
        PromptAssemblyService service = service(new EnvProperties(), root);
        RunRecallPolicy policy = RunRecallPolicy.builder()
                .workspaceContextEnabled(false)
                .workspaceKnowledgeEnabled(true)
                .historicalRagEnabled(false)
                .build();
        AgentRunContext context = AgentRunContext.builder()
                .originalInput("直退券没有退款按钮")
                .runForm(RunForm.DIAGNOSE)
                .sourceDomain(SourceType.DIAGNOSE)
                .agentType(AgentType.CLAUDE)
                .workingDir(root.toString())
                .recallPolicy(policy)
                .build();

        PromptAssemblyResult result = service.assemble(context);

        assertFalse(result.getPrompt().contains("[Workspace Context]"));
        assertTrue(result.getPrompt().contains("[候选知识]"));
        assertTrue(result.getWorkspaceContextDocs().isEmpty());
        assertTrue(result.getWorkspaceKnowledgeHits()
                .contains("docs/issue-log/issue/I-033-refund-button.md"));
    }

    @Test
    public void assemble_shouldSkipHistoricalRag_When_SourceDomainIsNotDiagnose() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        PromptAssemblyService service = service(new EnvProperties(), root);
        AgentRunContext context = AgentRunContext.builder()
                .originalInput("普通工作流问题")
                .runForm(RunForm.WORKFLOW_STEP)
                .sourceDomain(SourceType.GENERAL)
                .agentType(AgentType.CODEX)
                .workingDir(root.toString())
                .recallPolicy(RunRecallPolicy.builder()
                        .workspaceContextEnabled(true)
                        .workspaceKnowledgeEnabled(true)
                        .historicalRagEnabled(false)
                        .historicalSourceFilter(SourceType.GENERAL)
                        .build())
                .build();

        PromptAssemblyResult result = service.assemble(context);

        assertFalse(result.getPrompt().contains("[相似历史诊断]"));
        assertTrue(result.getRecallContributions().stream()
                .anyMatch(c -> c.getChannel() == RecallChannel.HISTORICAL_RAG
                        && !c.isEnabled()));
    }

    private RunRecallPolicy diagnosePolicy() {
        return RunRecallPolicy.builder()
                .workspaceContextEnabled(true)
                .workspaceKnowledgeEnabled(true)
                .historicalRagEnabled(true)
                .historicalSourceFilter(SourceType.DIAGNOSE)
                .topK(8)
                .build();
    }

    private PromptAssemblyService service(EnvProperties envProperties, Path root) {
        FsProperties fsProperties = new FsProperties();
        fsProperties.getRoots().add(root.toString());
        WorkspaceContextResolver resolver = new WorkspaceContextResolver(fsProperties);
        return new PromptAssemblyService(java.util.Arrays.asList(
                new EnvPromptContributor(envProperties),
                new WorkspaceContextContributor(resolver),
                new KnowledgePreRecallContributor(resolver),
                new HistoricalRagContributor(),
                new UserInputContributor(),
                new OutputInstructionContributor()
        ));
    }

    private EnvProperties envProperties(String key, String prompt) {
        EnvProperties properties = new EnvProperties();
        EnvProperties.EnvEntry entry = new EnvProperties.EnvEntry();
        entry.setKey(key);
        entry.setPrompt(prompt);
        properties.getEnvs().add(entry);
        return properties;
    }

    private AgentRunContext baseContext(Path root, String input) {
        return AgentRunContext.builder()
                .originalInput(input)
                .runForm(RunForm.DIAGNOSE)
                .sourceDomain(SourceType.DIAGNOSE)
                .agentType(AgentType.CLAUDE)
                .workingDir(root.toString())
                .recallPolicy(RunRecallPolicy.disabled())
                .build();
    }
}
