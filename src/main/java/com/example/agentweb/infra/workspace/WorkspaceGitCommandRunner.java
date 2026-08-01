package com.example.agentweb.infra.workspace;

import java.nio.file.Path;

/**
 * 固定 token Git 子进程执行边界，测试可替换以复现采集窗口变化。
 *
 * @author alex
 * @since 2026-08-01
 */
interface WorkspaceGitCommandRunner {

    WorkspaceGitCommandResult execute(Path directory, String... command);
}
