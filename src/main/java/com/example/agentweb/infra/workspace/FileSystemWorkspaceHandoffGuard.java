package com.example.agentweb.infra.workspace;

import com.example.agentweb.app.workbench.port.WorkspaceHandoffGuard;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 在主仓库 .gitignore 中追加 Workbench 自有产物条目，best-effort 不阻断创建。
 *
 * @author alex
 * @since 2026-08-06
 */
@Component
public class FileSystemWorkspaceHandoffGuard implements WorkspaceHandoffGuard {

    private static final Logger log = LoggerFactory.getLogger(
            FileSystemWorkspaceHandoffGuard.class);

    private static final String HANDOFF_IGNORE_ENTRY = ".workbench/handoff/";
    private static final String WORKTREE_IGNORE_ENTRY = ".worktrees/";
    private static final String GITIGNORE_FILE = ".gitignore";
    private static final String APPEND_HEADER =
            "\n# agent-web workbench artifacts\n";

    @Override
    public void ensureHandoffIgnored(RepositoryScope scope) {
        if (scope == null) {
            return;
        }
        ResolvedRepository primary = scope.primaryRepository();
        if (primary == null) {
            return;
        }
        ensureHandoffIgnored(Path.of(primary.getRepositoryRoot()));
    }

    /**
     * 按目录直接保障（统一入口的 Chat 会话无 RepositoryScope，直接传工作目录）。
     * best-effort：失败仅告警，不阻断交接导出。
     */
    public void ensureHandoffIgnored(Path workingDir) {
        if (workingDir == null) {
            return;
        }
        Path gitignore = workingDir.resolve(GITIGNORE_FILE);
        try {
            ensureEntries(gitignore);
        } catch (IOException failure) {
            log.warn("workbench-handoff-gitignore-failed repoRoot={} reason={}",
                    workingDir, failure.getMessage());
        }
    }

    private void ensureEntries(Path gitignore) throws IOException {
        String content = readExisting(gitignore);
        StringBuilder additions = new StringBuilder();
        if (!content.contains(HANDOFF_IGNORE_ENTRY)) {
            additions.append(HANDOFF_IGNORE_ENTRY).append('\n');
        }
        if (!content.contains(WORKTREE_IGNORE_ENTRY)) {
            additions.append(WORKTREE_IGNORE_ENTRY).append('\n');
        }
        if (additions.length() == 0) {
            return;
        }
        String prefix = content.isEmpty() ? APPEND_HEADER.stripLeading() : APPEND_HEADER;
        String appended = content + prefix + additions;
        Files.writeString(gitignore, appended, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private String readExisting(Path gitignore) throws IOException {
        if (!Files.isRegularFile(gitignore)) {
            return "";
        }
        return Files.readString(gitignore, StandardCharsets.UTF_8);
    }
}
