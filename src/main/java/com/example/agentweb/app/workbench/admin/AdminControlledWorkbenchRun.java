package com.example.agentweb.app.workbench.admin;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import lombok.Getter;

/**
 * 已完成 Admin exact binding 校验的 Workbench Run 应用内事实。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
final class AdminControlledWorkbenchRun {

    private final Workbench workbench;
    private final WorkbenchRunSnapshot snapshot;
    private final ChatRun run;

    private AdminControlledWorkbenchRun(
            Workbench workbench, WorkbenchRunSnapshot snapshot,
            ChatRun run) {
        this.workbench = workbench;
        this.snapshot = snapshot;
        this.run = run;
    }

    static AdminControlledWorkbenchRun verified(
            Workbench workbench, WorkbenchRunSnapshot snapshot,
            ChatRun run) {
        if (workbench == null || snapshot == null || run == null) {
            throw new IllegalArgumentException(
                    "admin controlled workbench run facts are required");
        }
        return new AdminControlledWorkbenchRun(
                workbench, snapshot, run);
    }
}
