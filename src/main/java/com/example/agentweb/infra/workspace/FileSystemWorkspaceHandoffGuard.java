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
 * 在主仓库 .gitignore 中追加 .workbench/handoff/ 条目，best-effort 不阻断创建。
 *
 * @author alex
 * @since 2026-08-06
 */
@Component
public class FileSystemWorkspaceHandoffGuard implements WorkspaceHandoffGuard {

    private static final Logger log = LoggerFactory.getLogger(
            FileSystemWorkspaceHandoffGuard.class);

    private static final String HANDOFF_IGNORE_ENTRY = ".workbench/handoff/";
    private static final String GITIGNORE_FILE = ".gitignore";
    private static final String APPEND_CONTENT =
            "\n# agent-web workbench handoff artifacts\n"
                    + HANDOFF_IGNORE_ENTRY + "\n";

    @Override
    public void ensureHandoffIgnored(RepositoryScope scope) {
        if (scope == null) {
            return;
        }
        ResolvedRepository primary = scope.primaryRepository();
        if (primary == null) {
            return;
        }
        Path repoRoot = Path.of(primary.getRepositoryRoot());
        Path gitignore = repoRoot.resolve(GITIGNORE_FILE);
        try {
            ensureEntry(gitignore);
        } catch (IOException failure) {
            log.warn("workbench-handoff-gitignore-failed repoRoot={} reason={}",
                    repoRoot, failure.getMessage());
        }
    }

    private void ensureEntry(Path gitignore) throws IOException {
        String content = readExisting(gitignore);
        if (content.contains(HANDOFF_IGNORE_ENTRY)) {
            return;
        }
        String appended = content.isEmpty()
                ? APPEND_CONTENT.stripLeading()
                : content + APPEND_CONTENT;
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
