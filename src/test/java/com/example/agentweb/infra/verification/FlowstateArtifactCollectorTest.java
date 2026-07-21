package com.example.agentweb.infra.verification;

import com.example.agentweb.adapter.verification.CollectedArtifact;
import com.example.agentweb.adapter.verification.CollectedVerification;
import com.example.agentweb.domain.verification.VerificationOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FlowstateArtifactCollector 轻集成(@TempDir 真实文件,不起 Spring、不 Mock):
 * 状态翻译、降级路径、超限落盘、旧文件名回退。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class FlowstateArtifactCollectorTest {

    private static final long DEFAULT_LIMIT = 64 * 1024;

    @TempDir
    Path tempDir;

    @Test
    public void collect_with_verified_state_and_all_artifacts_should_inline_three_kinds() throws IOException {
        // Given: 正常 .flowstate + failed_cases + verification-record
        writeWorktreeFile(".flowstate", "state: SWIMLANE_VERIFIED\n");
        writeChangeFile("add-feature", "failed_cases.json", "[]");
        writeChangeFile("add-feature", "verification-record.md", "# 验证记录");
        FlowstateArtifactCollector collector = new FlowstateArtifactCollector(DEFAULT_LIMIT);

        // When
        CollectedVerification result = collector.collect(tempDir.toString());

        // Then: 终态 VERIFIED,三条工件全部内联
        assertEquals(VerificationOutcome.VERIFIED, result.getOutcome());
        assertNull(result.getDegradeReason());
        assertEquals(3, result.getArtifacts().size());
        assertInlined(findByKind(result, "FLOWSTATE"), "state: SWIMLANE_VERIFIED\n");
        assertInlined(findByKind(result, "FAILED_CASES"), "[]");
        assertInlined(findByKind(result, "VERIFICATION_RECORD"), "# 验证记录");
    }

    @Test
    public void collect_with_deploy_failed_state_should_map_to_deploy_failed() throws IOException {
        // Given
        writeWorktreeFile(".flowstate", "state: DEPLOY_FAILED\n");
        FlowstateArtifactCollector collector = new FlowstateArtifactCollector(DEFAULT_LIMIT);

        // When
        CollectedVerification result = collector.collect(tempDir.toString());

        // Then
        assertEquals(VerificationOutcome.DEPLOY_FAILED, result.getOutcome());
        assertNull(result.getDegradeReason());
    }

    @Test
    public void collect_with_verify_blocked_state_should_map_to_blocked() throws IOException {
        // Given
        writeWorktreeFile(".flowstate", "state: VERIFY_BLOCKED\n");
        FlowstateArtifactCollector collector = new FlowstateArtifactCollector(DEFAULT_LIMIT);

        // When
        CollectedVerification result = collector.collect(tempDir.toString());

        // Then
        assertEquals(VerificationOutcome.BLOCKED, result.getOutcome());
        assertNull(result.getDegradeReason());
    }

    @Test
    public void collect_with_verify_failed_state_should_map_to_blocked() throws IOException {
        // Given: dianxiaoer fix-loop 中的用例失败态(M4.5 补漏,此前落 null 走退出码兜底)
        writeWorktreeFile(".flowstate", "state: VERIFY_FAILED\n");
        FlowstateArtifactCollector collector = new FlowstateArtifactCollector(DEFAULT_LIMIT);

        // When
        CollectedVerification result = collector.collect(tempDir.toString());

        // Then
        assertEquals(VerificationOutcome.BLOCKED, result.getOutcome());
        assertNull(result.getDegradeReason());
    }

    @Test
    public void collect_without_flowstate_should_degrade_but_keep_other_artifacts() throws IOException {
        // Given: 无 .flowstate,但 change 目录有其余两类工件
        writeChangeFile("fix-bug", "failed_cases.json", "[{\"case\":\"c1\"}]");
        writeChangeFile("fix-bug", "verification-record.md", "# 记录");
        FlowstateArtifactCollector collector = new FlowstateArtifactCollector(DEFAULT_LIMIT);

        // When
        CollectedVerification result = collector.collect(tempDir.toString());

        // Then: outcome 降级为 null,其余工件仍在
        assertNull(result.getOutcome());
        assertEquals("flowstate 文件缺失", result.getDegradeReason());
        assertEquals(2, result.getArtifacts().size());
        assertNotNull(findByKind(result, "FAILED_CASES"));
        assertNotNull(findByKind(result, "VERIFICATION_RECORD"));
    }

    @Test
    public void collect_with_broken_yaml_should_degrade_and_keep_raw_content() throws IOException {
        // Given: 烂 YAML
        writeWorktreeFile(".flowstate", "{{{");
        FlowstateArtifactCollector collector = new FlowstateArtifactCollector(DEFAULT_LIMIT);

        // When
        CollectedVerification result = collector.collect(tempDir.toString());

        // Then: 降级 + 原文仍入 artifacts(有排查价值)
        assertNull(result.getOutcome());
        assertNotNull(result.getDegradeReason());
        assertTrue(result.getDegradeReason().contains("YAML 解析失败"));
        CollectedArtifact flowstate = findByKind(result, "FLOWSTATE");
        assertNotNull(flowstate);
        assertEquals("{{{", flowstate.getContent());
    }

    @Test
    public void collect_with_content_over_limit_should_store_file_path_instead() throws IOException {
        // Given: 内联上限 16 字节,flowstate 内容超限
        writeWorktreeFile(".flowstate", "state: SWIMLANE_VERIFIED\n");
        FlowstateArtifactCollector collector = new FlowstateArtifactCollector(16);

        // When
        CollectedVerification result = collector.collect(tempDir.toString());

        // Then: content 不内联、filePath 落盘,但翻译不受影响
        assertEquals(VerificationOutcome.VERIFIED, result.getOutcome());
        CollectedArtifact flowstate = findByKind(result, "FLOWSTATE");
        assertNotNull(flowstate);
        assertNull(flowstate.getContent());
        assertNotNull(flowstate.getFilePath());
        assertTrue(flowstate.getFilePath().endsWith(".flowstate"));
    }

    @Test
    public void collect_should_fall_back_to_legacy_superspec_state_file() throws IOException {
        // Given: 只有旧文件名 .superspec-state
        writeWorktreeFile(".superspec-state", "state: READY_FOR_ARCHIVE\n");
        FlowstateArtifactCollector collector = new FlowstateArtifactCollector(DEFAULT_LIMIT);

        // When
        CollectedVerification result = collector.collect(tempDir.toString());

        // Then: 回退读取旧文件并正常翻译
        assertEquals(VerificationOutcome.VERIFIED, result.getOutcome());
        assertNull(result.getDegradeReason());
        assertNotNull(findByKind(result, "FLOWSTATE"));
    }

    @Test
    public void collect_with_unknown_state_should_degrade_with_original_value() throws IOException {
        // Given: state 值不在映射表
        writeWorktreeFile(".flowstate", "state: HALF_DONE\n");
        FlowstateArtifactCollector collector = new FlowstateArtifactCollector(DEFAULT_LIMIT);

        // When
        CollectedVerification result = collector.collect(tempDir.toString());

        // Then: 降级原因带原值
        assertNull(result.getOutcome());
        assertNotNull(result.getDegradeReason());
        assertTrue(result.getDegradeReason().contains("无法映射"));
        assertTrue(result.getDegradeReason().contains("HALF_DONE"));
    }

    private void writeWorktreeFile(String fileName, String content) throws IOException {
        Files.write(tempDir.resolve(fileName), content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeChangeFile(String changeName, String fileName, String content) throws IOException {
        Path changeDir = tempDir.resolve("openspec").resolve("changes").resolve(changeName);
        Files.createDirectories(changeDir);
        Files.write(changeDir.resolve(fileName), content.getBytes(StandardCharsets.UTF_8));
    }

    private CollectedArtifact findByKind(CollectedVerification result, String kind) {
        return result.getArtifacts().stream()
                .filter(artifact -> kind.equals(artifact.getKind()))
                .findFirst()
                .orElse(null);
    }

    private void assertInlined(CollectedArtifact artifact, String expectedContent) {
        assertNotNull(artifact);
        assertEquals(expectedContent, artifact.getContent());
        assertNull(artifact.getFilePath());
    }
}
