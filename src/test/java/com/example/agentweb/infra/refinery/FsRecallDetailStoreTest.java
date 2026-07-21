package com.example.agentweb.infra.refinery;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.refinery.RagChunk;
import com.example.agentweb.domain.refinery.RefinedContent;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.TrustTier;
import com.example.agentweb.domain.refinery.TtlCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物化落盘: 召回命中的 chunk 正文写到 workingDir 下临时文件, prompt 只注入路径.
 * Infra 轻量集成测试, 真实文件系统 + @TempDir, 不起 Spring.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
class FsRecallDetailStoreTest {

    @TempDir
    Path workingDir;

    private final FsRecallDetailStore store = new FsRecallDetailStore();

    @Test
    void store_writes_body_file_and_returns_relative_path() throws IOException {
        RagChunk hit = chunk("c1", null);

        List<String> paths = store.store(workingDir.toString(), Collections.singletonList(hit));

        assertEquals(1, paths.size());
        String rel = paths.get(0);
        assertTrue(rel.startsWith(".agent-web/recall/"), "相对路径应指向物化目录: " + rel);
        Path file = workingDir.resolve(rel);
        assertTrue(Files.isRegularFile(file), "物化文件应存在");
        String body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(body.contains("过程正文"), "文件应含 process 正文");
        assertTrue(body.contains("结论正文"), "文件应含 conclusion 正文");
        assertTrue(body.contains("场景正文"), "文件应含 context 正文");
        assertTrue(body.contains("c1"), "文件应含 chunk 溯源 id");
    }

    @Test
    void store_prefers_existing_detail_path_and_skips_materialization() {
        RagChunk hit = chunk("c1", "docs/issue-log/issue/I-001-xxx.md");

        List<String> paths = store.store(workingDir.toString(), Collections.singletonList(hit));

        assertEquals("docs/issue-log/issue/I-001-xxx.md", paths.get(0));
        assertFalse(Files.exists(workingDir.resolve(".agent-web")), "有既有 detail_path 时不物化");
    }

    @Test
    void store_null_working_dir_returns_null_paths_without_throwing() {
        List<String> paths = store.store(null, Collections.singletonList(chunk("c1", null)));

        assertEquals(1, paths.size());
        assertNull(paths.get(0));
    }

    @Test
    void store_prunes_run_dirs_older_than_seven_days() throws IOException {
        Path oldRun = workingDir.resolve(".agent-web/recall/old-run");
        Files.createDirectories(oldRun);
        Path oldFile = oldRun.resolve("1-x.md");
        Files.write(oldFile, "old".getBytes(StandardCharsets.UTF_8));
        FileTime eightDaysAgo = FileTime.from(Instant.now().minus(8, ChronoUnit.DAYS));
        Files.setLastModifiedTime(oldFile, eightDaysAgo);
        Files.setLastModifiedTime(oldRun, eightDaysAgo);

        store.store(workingDir.toString(), Collections.singletonList(chunk("c1", null)));

        assertFalse(Files.exists(oldRun), "超过 7 天的物化目录应被清理");
    }

    @Test
    void store_appends_git_info_exclude_once_when_working_dir_is_git_repo() throws IOException {
        Files.createDirectories(workingDir.resolve(".git/info"));

        store.store(workingDir.toString(), Collections.singletonList(chunk("c1", null)));
        store.store(workingDir.toString(), Collections.singletonList(chunk("c2", null)));

        Path exclude = workingDir.resolve(".git/info/exclude");
        assertTrue(Files.isRegularFile(exclude));
        String content = new String(Files.readAllBytes(exclude), StandardCharsets.UTF_8);
        int first = content.indexOf(".agent-web/");
        assertTrue(first >= 0, "git 仓库 workingDir 应写入 exclude 防护");
        assertEquals(first, content.lastIndexOf(".agent-web/"), "重复调用不应重复追加");
    }

    @Test
    void store_non_git_working_dir_skips_exclude_without_creating_git_dir() {
        store.store(workingDir.toString(), Collections.singletonList(chunk("c1", null)));

        assertFalse(Files.exists(workingDir.resolve(".git")), "非 git 目录不得创建 .git");
    }

    @Test
    void store_two_hits_get_distinct_sequenced_files() {
        List<RagChunk> hits = Arrays.asList(chunk("c1", null), chunk("c2", null));

        List<String> paths = store.store(workingDir.toString(), hits);

        assertEquals(2, paths.size());
        assertFalse(paths.get(0).equals(paths.get(1)), "两个命中应各自成文件");
        assertTrue(paths.get(0).contains("1-c1"), "文件名应含序号与 chunkId: " + paths.get(0));
        assertTrue(paths.get(1).contains("2-c2"), "文件名应含序号与 chunkId: " + paths.get(1));
    }

    private RagChunk chunk(String id, String detailPath) {
        return RagChunk.builder()
                .id(id)
                .sourceSessionId("s1")
                .agentType(AgentType.CLAUDE)
                .content(new RefinedContent("标题", Collections.singletonList("sig"),
                        "场景正文", "过程正文", "结论正文"))
                .score(0.8)
                .ttlCategory(TtlCategory.BUSINESS)
                .createdAt(Instant.parse("2026-06-01T00:00:00Z"))
                .embeddingModel("qwen")
                .embedding(new float[]{0.1f})
                .sourceType(SourceType.DIAGNOSE)
                .tier(TrustTier.PENDING)
                .env("test")
                .detailPath(detailPath)
                .build();
    }
}
