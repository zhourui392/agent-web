package com.example.agentweb.infra.issuelog;

import com.example.agentweb.domain.issuelog.IndexMetadata;
import com.example.agentweb.domain.issuelog.IssueLogDraft;
import com.example.agentweb.domain.issuelog.IssueLogEntry;
import com.example.agentweb.infra.issuelog.config.IssueLogProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileSystemIssueLogRepository} 集成测,覆盖目录初始化、id 分配、
 * 落盘格式、INDEX 追加、并发不冲突等关键路径。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
public class FileSystemIssueLogRepositoryTest {

    private FileSystemIssueLogRepository repo;

    @BeforeEach
    public void setUp() {
        IssueLogProperties props = new IssueLogProperties();
        IndexMetadataExtractor extractor = new IndexMetadataExtractor();
        IssueLogIndexHeaderLoader headerLoader = new IssueLogIndexHeaderLoader();
        repo = new FileSystemIssueLogRepository(props, extractor, headerLoader,
                new WorkingDirIssueLogLockRegistry());
    }

    @Test
    public void ensureInitialized_empty_dir_should_create_directories_and_header(@TempDir Path workingDir) throws IOException {
        repo.ensureInitialized(workingDir);

        Path issueDir = workingDir.resolve("docs/issue-log/issue");
        assertTrue(Files.isDirectory(issueDir), "issue 子目录应被创建");

        Path indexFile = workingDir.resolve("docs/issue-log/INDEX.md");
        assertTrue(Files.exists(indexFile), "INDEX.md 应被创建");

        String content = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
        assertTrue(content.contains("| ID | 标题 | 类型 | 服务 | 触发词 | 文件 |"), "应含含触发词列的标准表头");
        assertTrue(content.contains("|----|"), "应含分隔符行");
    }

    @Test
    public void ensureInitialized_legacy_5_column_index_should_upgrade_to_6_columns_filling_empty_trigger_cell(@TempDir Path workingDir) throws IOException {
        seedExistingIndex(workingDir, ""
                + "# Issue Log Index\n\n"
                + "| ID | 标题 | 类型 | 服务 | 文件 |\n"
                + "|----|------|------|------|------|\n"
                + "| I-001 | 老经验 | logic-pitfall | svc-a | [I-001](issue/I-001-x.md) |\n");

        repo.ensureInitialized(workingDir);

        Path indexFile = workingDir.resolve("docs/issue-log/INDEX.md");
        String content = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
        assertTrue(content.contains("| ID | 标题 | 类型 | 服务 | 触发词 | 文件 |"), "表头应升级为 6 列");
        assertTrue(content.contains("|----|------|------|------|--------|------|"), "分隔符行应升级");
        assertTrue(content.contains("| I-001 | 老经验 | logic-pitfall | svc-a |  | [I-001](issue/I-001-x.md) |"),
                "旧数据行应在服务与文件之间补空触发词单元");
    }

    @Test
    public void ensureInitialized_already_6_column_index_should_be_idempotent_and_not_rewrite(@TempDir Path workingDir) throws IOException {
        String fixture = ""
                + "# Index\n\n"
                + "| ID | 标题 | 类型 | 服务 | 触发词 | 文件 |\n"
                + "|----|------|------|------|--------|------|\n"
                + "| I-001 | 已升级 | x | s | a, b | [I-001](issue/X.md) |\n";
        seedExistingIndex(workingDir, fixture);

        repo.ensureInitialized(workingDir);

        Path indexFile = workingDir.resolve("docs/issue-log/INDEX.md");
        String content = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
        assertTrue(content.contains("| I-001 | 已升级 | x | s | a, b | [I-001](issue/X.md) |"),
                "新格式 INDEX 不应被改动");
    }

    @Test
    public void ensureInitialized_existing_index_should_be_preserved(@TempDir Path workingDir) throws IOException {
        seedExistingIndex(workingDir, ""
                + "# 自定义 Index\n\n"
                + "| ID | 标题 | 类型 | 服务 | 文件 |\n"
                + "|----|------|------|------|------|\n"
                + "| I-001 | 已有 | logic-pitfall | svc | [.](.) |\n");

        repo.ensureInitialized(workingDir);

        Path indexFile = workingDir.resolve("docs/issue-log/INDEX.md");
        String content = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
        assertTrue(content.contains("自定义 Index"), "已存在 INDEX 不应被覆盖");
        assertTrue(content.contains("I-001"), "已有数据行应保留");
    }

    @Test
    public void nextId_empty_index_should_return_I_001(@TempDir Path workingDir) {
        repo.ensureInitialized(workingDir);

        assertEquals("I-001", repo.nextId(workingDir));
    }

    @Test
    public void nextId_with_22_existing_rows_should_return_I_023(@TempDir Path workingDir) throws IOException {
        repo.ensureInitialized(workingDir);
        appendRealSampleIndex(workingDir, 22);

        assertEquals("I-023", repo.nextId(workingDir));
    }

    @Test
    public void nextId_with_non_standard_id_should_ignore_and_use_max_legal_id_plus_1(@TempDir Path workingDir) throws IOException {
        seedExistingIndex(workingDir, ""
                + "| ID | 标题 | 类型 | 服务 | 文件 |\n"
                + "|----|------|------|------|------|\n"
                + "| I-005 | 标准 | x | svc | [.](.) |\n"
                + "| X-99 | 非标准 | y | svc | [.](.) |\n");

        assertEquals("I-006", repo.nextId(workingDir));
    }

    @Test
    public void save_should_persist_issue_file_and_append_index_row(@TempDir Path workingDir) throws IOException {
        repo.ensureInitialized(workingDir);
        IssueLogDraft draft = new IssueLogDraft(
                "签到页打不开",
                Arrays.asList("logic-pitfall", "config-trap"),
                Arrays.asList("activity-gateway", "checkin-service"),
                "用户反馈签到页 502",
                "task_play 全部过期",
                "下线过期配置",
                "");

        IssueLogEntry entry = repo.save(workingDir, draft);

        // 1. id 与文件路径
        assertEquals("I-001", entry.getId());
        Path issueFile = workingDir.resolve("docs/issue-log/issue").resolve(
                java.nio.file.Paths.get(entry.getFilePath()).getFileName().toString());
        assertTrue(Files.exists(issueFile), "issue 文件应落盘");

        // 2. issue 文件正文格式
        String body = new String(Files.readAllBytes(issueFile), StandardCharsets.UTF_8);
        assertTrue(body.contains("# I-001 - 签到页打不开"));
        assertTrue(body.contains("**类型**: logic-pitfall/config-trap"));
        assertTrue(body.contains("**服务**: activity-gateway / checkin-service"));
        assertTrue(body.contains("**现象**: 用户反馈签到页 502"));
        assertTrue(body.contains("**根因**: task_play 全部过期"));
        assertTrue(body.contains("**解决**: 下线过期配置"));

        // 3. INDEX 追加了一行
        String indexContent = new String(Files.readAllBytes(workingDir.resolve("docs/issue-log/INDEX.md")),
                StandardCharsets.UTF_8);
        assertTrue(indexContent.contains("| I-001 |"), "INDEX 应含新行");
        assertTrue(indexContent.contains("logic-pitfall/config-trap"));
        assertTrue(indexContent.contains("activity-gateway / checkin-service"));
        assertTrue(indexContent.contains("[I-001](issue/"), "INDEX 链接应指向 issue 子目录");
    }

    @Test
    public void save_empty_notes_should_omit_notes_line(@TempDir Path workingDir) throws IOException {
        repo.ensureInitialized(workingDir);
        IssueLogEntry entry = repo.save(workingDir, new IssueLogDraft(
                "x", Arrays.asList("logic-pitfall"), Arrays.asList("svc"),
                "p", "r", "s", ""));

        Path issueFile = workingDir.resolve(entry.getFilePath());
        String body = new String(Files.readAllBytes(issueFile), StandardCharsets.UTF_8);
        // notes 为空时不渲染该字段行
        assertTrue(!body.contains("**注意**"), "空 notes 不应渲染");
    }

    @Test
    public void save_concurrent_twice_should_allocate_distinct_ids_without_conflict(@TempDir Path workingDir) throws Exception {
        repo.ensureInitialized(workingDir);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<String> id1 = new AtomicReference<>();
        AtomicReference<String> id2 = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> raceSave(workingDir, "题目 A", start, done, id1, err));
            pool.submit(() -> raceSave(workingDir, "题目 B", start, done, id2, err));
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "并发 save 应在 10s 内完成");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(null, err.get(), "并发不应抛错");
        assertNotEquals(id1.get(), id2.get(), "两个 id 必须不同");
        Set<String> ids = new HashSet<>();
        ids.add(id1.get());
        ids.add(id2.get());
        assertEquals(new HashSet<>(Arrays.asList("I-001", "I-002")), ids, "应为 I-001/I-002");
    }

    @Test
    public void loadMetadata_should_aggregate_existing_categories_and_services_from_index(@TempDir Path workingDir) throws IOException {
        repo.ensureInitialized(workingDir);
        // count=5 覆盖全部 5 种类型/服务
        appendRealSampleIndex(workingDir, 5);

        IndexMetadata meta = repo.loadMetadata(workingDir);

        assertTrue(meta.getCategories().contains("key-format"),
                "categories: " + meta.getCategories());
        assertTrue(meta.getServices().contains("notification-service"),
                "services: " + meta.getServices());
    }

    @Test
    public void save_chinese_title_slug_should_preserve_chinese_characters(@TempDir Path workingDir) throws IOException {
        repo.ensureInitialized(workingDir);
        IssueLogEntry entry = repo.save(workingDir, new IssueLogDraft(
                "签到页 502 ERROR",
                Arrays.asList("logic-pitfall"),
                Arrays.asList("svc"),
                "", "", "", ""));

        // 文件名应包含中文 + 字母数字,符号被剥离
        String fileName = java.nio.file.Paths.get(entry.getFilePath()).getFileName().toString();
        assertTrue(fileName.startsWith("I-001-"), "前缀: " + fileName);
        assertTrue(fileName.contains("签到页"), "应保留中文: " + fileName);
        assertTrue(fileName.endsWith(".md"));
    }

    @Test
    public void save_draft_with_slug_should_use_kebab_slug_for_filename(@TempDir Path workingDir) throws IOException {
        repo.ensureInitialized(workingDir);
        IssueLogEntry entry = repo.save(workingDir, new IssueLogDraft(
                "兑换抽奖通道订单硬规则不可退",
                Arrays.asList("logic-pitfall"),
                Arrays.asList("svc"),
                Arrays.asList("退款按钮不显示"),
                "", "", "", "",
                "exchange-lottery-no-refund"));

        String fileName = java.nio.file.Paths.get(entry.getFilePath()).getFileName().toString();
        assertEquals("I-001-exchange-lottery-no-refund.md", fileName,
                "有 slug 时文件名应使用英文 kebab slug 而非中文标题");
    }

    @Test
    public void save_draft_slug_exceeding_max_length_should_be_truncated(@TempDir Path workingDir) throws IOException {
        repo.ensureInitialized(workingDir);
        StringBuilder longSlug = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            longSlug.append("part").append(i).append('-');
        }
        IssueLogEntry entry = repo.save(workingDir, new IssueLogDraft(
                "标题", Arrays.asList("logic-pitfall"), Arrays.asList("svc"),
                Arrays.asList("词"),
                "", "", "", "",
                longSlug.toString()));

        String fileName = java.nio.file.Paths.get(entry.getFilePath()).getFileName().toString();
        // I-001- 前缀 + slug(≤50) + .md
        assertTrue(fileName.length() <= "I-001-".length() + 50 + ".md".length(),
                "slug 应按 slugMaxLength 截断: " + fileName);
    }

    private void raceSave(Path workingDir, String title, CountDownLatch start, CountDownLatch done,
                          AtomicReference<String> idSink, AtomicReference<Throwable> errSink) {
        try {
            start.await();
            IssueLogEntry e = repo.save(workingDir, new IssueLogDraft(
                    title, Arrays.asList("logic-pitfall"), Arrays.asList("svc"),
                    "", "", "", ""));
            idSink.set(e.getId());
        } catch (Throwable t) {
            errSink.set(t);
        } finally {
            done.countDown();
        }
    }

    private void seedExistingIndex(Path workingDir, String content) throws IOException {
        Path indexFile = workingDir.resolve("docs/issue-log/INDEX.md");
        Files.createDirectories(indexFile.getParent());
        Files.createDirectories(workingDir.resolve("docs/issue-log/issue"));
        Files.write(indexFile, content.getBytes(StandardCharsets.UTF_8));
    }

    private void appendRealSampleIndex(Path workingDir, int count) throws IOException {
        Path indexFile = workingDir.resolve("docs/issue-log/INDEX.md");
        StringBuilder sb = new StringBuilder();
        String[] cats = {"key-format", "tool-missing", "logic-pitfall", "env-diff", "config-trap"};
        String[] svcs = {"notification-service", "inventory-service", "payment-service",
                "payment-channel", "risk-service"};
        for (int i = 1; i <= count; i++) {
            sb.append(String.format("| I-%03d | 标题%d | %s | %s | [I-%03d](issue/I-%03d.md) |%n",
                    i, i, cats[i % cats.length], svcs[i % svcs.length], i, i));
        }
        Files.write(indexFile, sb.toString().getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
    }
}
