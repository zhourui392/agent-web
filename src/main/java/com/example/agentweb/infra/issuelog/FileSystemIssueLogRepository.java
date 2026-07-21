package com.example.agentweb.infra.issuelog;

import com.example.agentweb.domain.issuelog.IndexMetadata;
import com.example.agentweb.domain.issuelog.IssueLogDraft;
import com.example.agentweb.domain.issuelog.IssueLogEntry;
import com.example.agentweb.domain.issuelog.IssueLogRepository;
import com.example.agentweb.infra.issuelog.config.IssueLogProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * issue-log 仓储的文件系统实现。读写工作目录下 {@code docs/issue-log/} 目录树。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
@Component
public class FileSystemIssueLogRepository implements IssueLogRepository {

    private static final Pattern ID_LINE = Pattern.compile("^\\s*\\|\\s*I-(\\d+)\\s*\\|");
    private static final Pattern SLUG_INVALID = Pattern.compile("[^a-zA-Z0-9\\u4e00-\\u9fff_-]");
    /** markdown 表头分隔符行(仅含 |、空白、-、:),用于 INDEX 升级时区分表头/数据行。 */
    private static final Pattern SEPARATOR_LINE = Pattern.compile("^\\s*\\|[\\s\\-:|]+\\|\\s*$");
    private static final String DEFAULT_SLUG = "untitled";

    private final IssueLogProperties props;
    private final IndexMetadataExtractor metadataExtractor;
    private final IssueLogIndexHeaderLoader headerLoader;
    private final WorkingDirIssueLogLockRegistry lockRegistry;

    public FileSystemIssueLogRepository(IssueLogProperties props,
                                        IndexMetadataExtractor metadataExtractor,
                                        IssueLogIndexHeaderLoader headerLoader,
                                        WorkingDirIssueLogLockRegistry lockRegistry) {
        this.props = props;
        this.metadataExtractor = metadataExtractor;
        this.headerLoader = headerLoader;
        this.lockRegistry = lockRegistry;
    }

    @Override
    public void ensureInitialized(Path workingDir) {
        Path issuesDir = resolveIssuesDir(workingDir);
        Path indexFile = resolveIndexFile(workingDir);
        try {
            Files.createDirectories(issuesDir);
            if (!Files.exists(indexFile)) {
                Files.write(indexFile, headerLoader.load().getBytes(StandardCharsets.UTF_8));
            } else {
                upgradeIndexIfMissingTriggerColumn(indexFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to initialize issue-log layout under " + workingDir, e);
        }
    }

    /**
     * 历史 INDEX 是 5 列格式 (ID | 标题 | 类型 | 服务 | 文件),新版本加了"触发词"列。
     * 幂等检测:若表头不含"触发词",原地升级——表头改 6 列、每行数据补一个空触发词单元。
     * 已经是新格式的 INDEX 不动。
     */
    private void upgradeIndexIfMissingTriggerColumn(Path indexFile) throws IOException {
        List<String> lines = Files.readAllLines(indexFile, StandardCharsets.UTF_8);
        int headerIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.trim().startsWith("| ID ") || l.trim().startsWith("|ID")) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0 || lines.get(headerIdx).contains("触发词")) {
            return;
        }
        List<String> upgraded = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == headerIdx) {
                upgraded.add("| ID | 标题 | 类型 | 服务 | 触发词 | 文件 |");
            } else if (i == headerIdx + 1 && SEPARATOR_LINE.matcher(line).matches()) {
                upgraded.add("|----|------|------|------|--------|------|");
            } else if (i > headerIdx && line.trim().startsWith("|") && !SEPARATOR_LINE.matcher(line).matches()) {
                upgraded.add(insertEmptyTriggerCell(line));
            } else {
                upgraded.add(line);
            }
        }
        // 仅在有变化时回写,避免无谓 IO
        if (!upgraded.equals(lines)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < upgraded.size(); i++) {
                sb.append(upgraded.get(i));
                if (i < upgraded.size() - 1 || lines.size() > 0) {
                    sb.append('\n');
                }
            }
            Files.write(indexFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 在旧 5 列数据行的"服务"和"文件"之间插一个空"触发词"单元。 */
    private String insertEmptyTriggerCell(String row) {
        // 分列时保留首尾的空段,确保列位稳定
        String[] cols = row.split("\\|", -1);
        // 期望旧格式 = ["", " ID ", " 标题 ", " 类型 ", " 服务 ", " 文件 ", ""] 共 7 段
        if (cols.length != 7) {
            return row;  // 列数已经不是 5,可能用户手工编辑过,不动
        }
        return cols[0] + "|" + cols[1] + "|" + cols[2] + "|" + cols[3] + "|"
                + cols[4] + "|  |" + cols[5] + "|" + cols[6];
    }

    @Override
    public String nextId(Path workingDir) {
        Path indexFile = resolveIndexFile(workingDir);
        if (!Files.exists(indexFile)) {
            return formatId(1);
        }
        int max = 0;
        try {
            List<String> lines = Files.readAllLines(indexFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                Matcher m = ID_LINE.matcher(line);
                if (m.find()) {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read INDEX.md", e);
        }
        return formatId(max + 1);
    }

    @Override
    public IssueLogEntry save(Path workingDir, IssueLogDraft draft) {
        ReentrantLock lock = lockRegistry.obtain(workingDir);
        lock.lock();
        try {
            ensureInitialized(workingDir);
            String id = nextId(workingDir);
            String slug = resolveSlug(draft);
            String fileName = id + "-" + slug + ".md";
            Path issueFile = resolveIssuesDir(workingDir).resolve(fileName);
            writeIssueFile(issueFile, id, draft);
            appendIndexRow(workingDir, id, draft, fileName);
            String relativePath = relativeFromWorkingDir(workingDir, issueFile);
            return new IssueLogEntry(id, draft, relativePath, Instant.now());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public IndexMetadata loadMetadata(Path workingDir) {
        return metadataExtractor.extract(resolveIndexFile(workingDir));
    }

    @Override
    public String loadIndexText(Path workingDir) {
        Path indexFile = resolveIndexFile(workingDir);
        if (!Files.exists(indexFile)) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read INDEX.md " + indexFile, e);
        }
    }

    private void writeIssueFile(Path issueFile, String id, IssueLogDraft draft) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("# ").append(id).append(" - ").append(draft.getTitle()).append("\n\n");
        sb.append("- **类型**: ").append(String.join("/", draft.getCategories())).append('\n');
        sb.append("- **服务**: ").append(String.join(" / ", draft.getServices())).append('\n');
        if (!draft.getTriggerSignals().isEmpty()) {
            sb.append("- **触发词**: ").append(String.join(", ", draft.getTriggerSignals())).append('\n');
        }
        sb.append("- **现象**: ").append(draft.getPhenomenon()).append('\n');
        sb.append("- **根因**: ").append(draft.getRootCause()).append('\n');
        sb.append("- **解决**: ").append(draft.getSolution()).append('\n');
        if (!draft.getNotes().isEmpty()) {
            sb.append("- **注意**: ").append(draft.getNotes()).append('\n');
        }
        try {
            Files.write(issueFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write issue file " + issueFile, e);
        }
    }

    private void appendIndexRow(Path workingDir, String id, IssueLogDraft draft, String fileName) {
        Path indexFile = resolveIndexFile(workingDir);
        String triggerCell = draft.getTriggerSignals().isEmpty()
                ? ""
                : String.join(", ", draft.getTriggerSignals());
        String row = String.format("| %s | %s | %s | %s | %s | [%s](%s/%s) |%n",
                id,
                draft.getTitle(),
                String.join("/", draft.getCategories()),
                String.join(" / ", draft.getServices()),
                triggerCell,
                id,
                props.getIssuesSubdir(),
                fileName);
        try {
            ensureTrailingNewline(indexFile);
            Files.write(indexFile, row.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append INDEX.md", e);
        }
    }

    private void ensureTrailingNewline(Path indexFile) throws IOException {
        long size = Files.size(indexFile);
        if (size == 0) {
            return;
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(indexFile.toFile(), "r")) {
            raf.seek(size - 1);
            int last = raf.read();
            if (last != '\n') {
                Files.write(indexFile, "\n".getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.APPEND);
            }
        }
    }

    /**
     * 文件名 slug 决策:草稿自带英文 kebab slug(域内已归一化)优先;
     * 缺失时回落到标题 slug 化(历史行为,中文标题会产出中文文件名)。
     */
    private String resolveSlug(IssueLogDraft draft) {
        String provided = draft.getSlug();
        if (provided != null) {
            return truncateSlug(provided);
        }
        return toSlug(draft.getTitle());
    }

    private String toSlug(String title) {
        String cleaned = truncateSlug(SLUG_INVALID.matcher(title).replaceAll(""));
        return cleaned.isEmpty() ? DEFAULT_SLUG : cleaned;
    }

    private String truncateSlug(String slug) {
        return slug.length() > props.getSlugMaxLength()
                ? slug.substring(0, props.getSlugMaxLength())
                : slug;
    }

    private String formatId(int num) {
        return String.format("I-%03d", num);
    }

    private Path resolveIssuesDir(Path workingDir) {
        return workingDir.resolve(props.getDocsDir()).resolve(props.getIssuesSubdir());
    }

    private Path resolveIndexFile(Path workingDir) {
        return workingDir.resolve(props.getDocsDir()).resolve(props.getIndexFile());
    }

    private String relativeFromWorkingDir(Path workingDir, Path target) {
        Path rel = workingDir.toAbsolutePath().relativize(target.toAbsolutePath());
        return rel.toString().replace('\\', '/');
    }
}
