package com.example.agentweb.app.agentrun;

import com.example.agentweb.app.setting.WorkspaceSettingsQueryService;
import com.example.agentweb.domain.setting.WorkspaceSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public class WorkspaceContextResolverTest {

    @TempDir
    Path tempDir;

    @Test
    public void resolve_withoutManifest_discoversDefaultIndexesUpToAllowedRoot() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Path serviceDir = Files.createDirectories(root.resolve("service-a"));
        Files.createDirectories(root.resolve("docs/issue-log"));
        Files.write(root.resolve("docs/issue-log/INDEX.md"),
                "- [I-001 支付失败](issue/I-001-pay.md)\n".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("docs/playbooks"));
        Files.write(root.resolve("docs/playbooks/INDEX.md"),
                "- [支付排查](pay.md)\n".getBytes(StandardCharsets.UTF_8));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(workspaceSettings(root));

        WorkspaceContext context = resolver.resolve(serviceDir.toString());

        assertEquals(root.toAbsolutePath().normalize(), context.getWorkspaceRoot());
        assertEquals(serviceDir.toAbsolutePath().normalize(), context.getWorkingDir());
        assertEquals(2, context.getKnowledgeIndexes().size());
        assertEquals("docs/issue-log/INDEX.md", context.getKnowledgeIndexes().get(0).getRelativePath());
        assertEquals("docs/playbooks/INDEX.md", context.getKnowledgeIndexes().get(1).getRelativePath());
    }

    @Test
    public void resolve_manifestRecallMinTier_parsedIntoContext() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Files.createDirectories(root.resolve("docs/issue-log"));
        Files.write(root.resolve("docs/issue-log/INDEX.md"),
                "- [I-001 支付失败](issue/I-001-pay.md)\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve(".agent-web.yml"), (""
                + "name: project\n"
                + "recall:\n"
                + "  min_tier: PENDING\n").getBytes(StandardCharsets.UTF_8));
        WorkspaceContextResolver resolver = new WorkspaceContextResolver(workspaceSettings(root));

        WorkspaceContext context = resolver.resolve(root.toString());

        assertEquals(com.example.agentweb.domain.refinery.TrustTier.PENDING,
                context.getRecallMinTier(), "manifest recall.min_tier 应解析进 WorkspaceContext");
    }

    @Test
    public void resolve_manifestRecallMinTierInvalid_degradesToNull() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo2"));
        Files.createDirectories(root.resolve("docs/issue-log"));
        Files.write(root.resolve("docs/issue-log/INDEX.md"),
                "- [I-001 支付失败](issue/I-001-pay.md)\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve(".agent-web.yml"), (""
                + "recall:\n"
                + "  min_tier: SUPER_TRUSTED\n").getBytes(StandardCharsets.UTF_8));
        WorkspaceContextResolver resolver = new WorkspaceContextResolver(workspaceSettings(root));

        WorkspaceContext context = resolver.resolve(root.toString());

        assertEquals(null, context.getRecallMinTier(), "非法取值降级为不限制而非启动失败");
    }

    @Test
    public void resolve_shouldIgnoreAgentsAndClaudeFiles() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Files.write(root.resolve("AGENTS.md"), "agent rules".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("CLAUDE.md"), "claude rules".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("docs/known-issues"));
        Files.write(root.resolve("docs/known-issues/INDEX.md"),
                "- [登录失败](issue/I-002-login.md)\n".getBytes(StandardCharsets.UTF_8));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(workspaceSettings(root));

        WorkspaceContext context = resolver.resolve(root.toString());

        assertEquals(1, context.getKnowledgeIndexes().size());
        assertFalse(context.summary().contains("AGENTS.md"));
        assertFalse(context.summary().contains("CLAUDE.md"));
        assertTrue(context.summary().contains("docs/known-issues/INDEX.md"));
    }

    @Test
    public void resolve_manifestAddsIndexesAndProdGuardrail() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Files.createDirectories(root.resolve("docs/custom"));
        Files.write(root.resolve("docs/custom/INDEX.md"),
                "- [库存失败](stock.md)\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve(".agent-web.yml"), String.join("\n",
                "name: project",
                "description: Project readonly analysis workspace",
                "knowledge_indexes:",
                "  - name: custom",
                "    path: docs/custom/INDEX.md",
                "    top_k: 5",
                "guardrails:",
                "  prod:",
                "    readonly: true",
                "    prompt: \"生产只读，不允许写操作\"").getBytes(StandardCharsets.UTF_8));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(workspaceSettings(root));

        WorkspaceContext context = resolver.resolve(root.toString());

        assertEquals("project", context.getName());
        assertEquals(1, context.getKnowledgeIndexes().size());
        assertEquals("custom", context.getKnowledgeIndexes().get(0).getName());
        assertEquals(5, context.getKnowledgeIndexes().get(0).getTopK());
        assertTrue(context.guardrailFor("prod").isPresent());
        assertTrue(context.guardrailFor("prod").get().isReadonly());
        assertEquals("生产只读，不允许写操作", context.guardrailFor("prod").get().getPrompt());
    }

    @Test
    public void resolve_manifestSupportsYamlBlockScalarAndFlexibleIndentation() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Files.createDirectories(root.resolve("docs/custom"));
        Files.write(root.resolve("docs/custom/INDEX.md"),
                "- [库存失败](stock.md)\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve(".agent-web.yml"), String.join("\n",
                "name: project",
                "description: |",
                "  Project readonly analysis workspace",
                "knowledge_indexes:",
                "    - name: custom",
                "      path: docs/custom/INDEX.md",
                "      top_k: \"6\"",
                "guardrails:",
                "    prod:",
                "      readonly: \"true\"",
                "      prompt: |",
                "        第一行只读约束",
                "        第二行必须核实证据").getBytes(StandardCharsets.UTF_8));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(workspaceSettings(root));

        WorkspaceContext context = resolver.resolve(root.toString());

        assertEquals("project", context.getName());
        assertEquals("Project readonly analysis workspace", context.getDescription());
        assertEquals(1, context.getKnowledgeIndexes().size());
        assertEquals(6, context.getKnowledgeIndexes().get(0).getTopK());
        assertTrue(context.guardrailFor("prod").isPresent());
        assertTrue(context.guardrailFor("prod").get().isReadonly());
        assertTrue(context.guardrailFor("prod").get().getPrompt().contains("第一行只读约束"));
        assertTrue(context.guardrailFor("prod").get().getPrompt().contains("第二行必须核实证据"));
    }

    @Test
    public void resolve_prefersNearestWorkspaceMarker() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Files.createDirectories(root.resolve("docs/issue-log"));
        Files.write(root.resolve("docs/issue-log/INDEX.md"),
                "- [父级问题](issue/I-parent.md)\n".getBytes(StandardCharsets.UTF_8));

        Path child = Files.createDirectories(root.resolve("worktrees/feature"));
        Files.createDirectories(child.resolve("docs/custom"));
        Files.write(child.resolve("docs/custom/INDEX.md"),
                "- [子项目问题](child.md)\n".getBytes(StandardCharsets.UTF_8));
        Files.write(child.resolve(".agent-web.yml"), String.join("\n",
                "name: child-workspace",
                "knowledge_indexes:",
                "  - name: child",
                "    path: docs/custom/INDEX.md").getBytes(StandardCharsets.UTF_8));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(workspaceSettings(root));

        WorkspaceContext context = resolver.resolve(child.resolve("module-a").toString());

        assertEquals(child.toAbsolutePath().normalize(), context.getWorkspaceRoot());
        assertEquals("child-workspace", context.getName());
        assertEquals(1, context.getKnowledgeIndexes().size());
        assertEquals("docs/custom/INDEX.md", context.getKnowledgeIndexes().get(0).getRelativePath());
    }

    @Test
    public void resolve_outsideConfiguredRoot_stopsAtWorkingDirectory() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Path outsideRoot = Files.createDirectory(tempDir.resolve("outside"));
        Files.createDirectories(outsideRoot.resolve("docs/issue-log"));
        Files.write(outsideRoot.resolve("docs/issue-log/INDEX.md"),
                "- [不应越界](issue/I-999.md)\n".getBytes(StandardCharsets.UTF_8));

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(workspaceSettings(root));

        WorkspaceContext context = resolver.resolve(outsideRoot.toString());

        assertEquals(outsideRoot.toAbsolutePath().normalize(), context.getWorkspaceRoot());
        assertTrue(context.getKnowledgeIndexes().isEmpty());
    }

    private WorkspaceSettingsQueryService workspaceSettings(Path root) {
        return () -> WorkspaceSettings.create(root.toString(), Collections.singletonList(root.toString()),
                Collections.<String>emptyList());
    }
}
