package com.example.agentweb.infra.refinery;

import com.example.agentweb.app.refinery.RecallDetailStore;
import com.example.agentweb.domain.refinery.RagChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link RecallDetailStore} 的文件系统实现: 物化到 {@code <workingDir>/.agent-web/recall/<runId>/},
 * 按 mtime 清理 7 天前的 run 目录; workingDir 是 git 仓库时把 {@code .agent-web/} 幂等写入
 * {@code .git/info/exclude} (不侵入工作区文件). 任何 IO 失败降级为 null 路径, 不抛出.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
@Component
@Slf4j
public class FsRecallDetailStore implements RecallDetailStore {

    private static final String RECALL_DIR = ".agent-web/recall";
    private static final String EXCLUDE_ENTRY = ".agent-web/";
    private static final int RETENTION_DAYS = 7;

    @Override
    public List<String> store(String workingDir, List<RagChunk> hits) {
        List<String> paths = new ArrayList<>(hits.size());
        for (RagChunk hit : hits) {
            paths.add(hit.getDetailPath());
        }
        if (workingDir == null || workingDir.trim().isEmpty() || !needsMaterialization(paths)) {
            return paths;
        }
        try {
            materialize(Paths.get(workingDir), hits, paths);
        } catch (IOException | RuntimeException e) {
            log.warn("recall-detail-store-failed workingDir={} reason={}", workingDir, e.getMessage());
        }
        return paths;
    }

    private boolean needsMaterialization(List<String> paths) {
        for (String path : paths) {
            if (path == null) {
                return true;
            }
        }
        return false;
    }

    private void materialize(Path root, List<RagChunk> hits, List<String> paths) throws IOException {
        Path recallRoot = root.resolve(RECALL_DIR);
        pruneExpiredRuns(recallRoot);
        ensureGitExclude(root);
        Path runDir = recallRoot.resolve(UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(runDir);
        for (int i = 0; i < hits.size(); i++) {
            if (paths.get(i) != null) {
                continue;
            }
            RagChunk hit = hits.get(i);
            Path file = runDir.resolve((i + 1) + "-" + hit.getId() + ".md");
            Files.write(file, renderBody(hit).getBytes(StandardCharsets.UTF_8));
            paths.set(i, root.relativize(file).toString().replace('\\', '/'));
        }
    }

    private void pruneExpiredRuns(Path recallRoot) {
        if (!Files.isDirectory(recallRoot)) {
            return;
        }
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        try (DirectoryStream<Path> runs = Files.newDirectoryStream(recallRoot)) {
            for (Path run : runs) {
                if (Files.getLastModifiedTime(run).toInstant().isBefore(cutoff)) {
                    FileSystemUtils.deleteRecursively(run);
                }
            }
        } catch (IOException e) {
            log.warn("recall-detail-prune-failed dir={} reason={}", recallRoot, e.getMessage());
        }
    }

    private void ensureGitExclude(Path root) {
        Path gitDir = root.resolve(".git");
        if (!Files.isDirectory(gitDir)) {
            return;
        }
        try {
            Path exclude = gitDir.resolve("info/exclude");
            Files.createDirectories(exclude.getParent());
            if (Files.exists(exclude)
                    && new String(Files.readAllBytes(exclude), StandardCharsets.UTF_8).contains(EXCLUDE_ENTRY)) {
                return;
            }
            Files.write(exclude, (EXCLUDE_ENTRY + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("recall-detail-git-exclude-failed root={} reason={}", root, e.getMessage());
        }
    }

    private String renderBody(RagChunk hit) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(hit.getContent().getTitle()).append("\n\n");
        sb.append("- chunk: ").append(hit.getId()).append('\n');
        sb.append("- 来源: ").append(hit.getSourceType().name())
                .append(' ').append(hit.getSourceSessionId()).append('\n');
        sb.append("- 可信度: ").append(hit.getTier().name())
                .append("，评分: ").append(hit.getScore()).append('\n');
        sb.append("- 时间: ").append(hit.getCreatedAt()).append("\n\n");
        appendSection(sb, "场景", hit.getContent().getContext());
        appendSection(sb, "过程", hit.getContent().getProcess());
        appendSection(sb, "结论", hit.getContent().getConclusion());
        return sb.toString();
    }

    private void appendSection(StringBuilder sb, String heading, String body) {
        if (body == null || body.trim().isEmpty()) {
            return;
        }
        sb.append("## ").append(heading).append("\n\n").append(body).append("\n\n");
    }
}
