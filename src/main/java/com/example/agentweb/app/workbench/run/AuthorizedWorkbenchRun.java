package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import lombok.Getter;

/**
 * 已完成 Owner 与 exact Run binding 校验的应用内事实。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
final class AuthorizedWorkbenchRun {

    private final Workbench workbench;
    private final WorkbenchStageRunSnapshot snapshot;
    private final ChatRun run;

    private AuthorizedWorkbenchRun(
            Workbench workbench, WorkbenchStageRunSnapshot snapshot,
            ChatRun run) {
        this.workbench = workbench;
        this.snapshot = snapshot;
        this.run = run;
    }

    static AuthorizedWorkbenchRun verified(
            Workbench workbench,
            WorkbenchStageRunSnapshot snapshot,
            ChatRun run) {
        if (workbench == null || snapshot == null || run == null) {
            throw new IllegalArgumentException(
                    "authorized workbench run facts are required");
        }
        return new AuthorizedWorkbenchRun(workbench, snapshot, run);
    }
}
