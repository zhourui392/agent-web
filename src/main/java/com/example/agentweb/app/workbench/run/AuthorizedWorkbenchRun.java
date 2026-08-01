package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
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
    private final WorkbenchRunSnapshot snapshot;
    private final ChatRun run;

    private AuthorizedWorkbenchRun(
            Workbench workbench, WorkbenchRunSnapshot snapshot,
            ChatRun run) {
        this.workbench = workbench;
        this.snapshot = snapshot;
        this.run = run;
    }

    static AuthorizedWorkbenchRun verified(
            Workbench workbench, WorkbenchRunSnapshot snapshot,
            ChatRun run) {
        if (workbench == null || snapshot == null || run == null) {
            throw new IllegalArgumentException(
                    "authorized workbench run facts are required");
        }
        return new AuthorizedWorkbenchRun(workbench, snapshot, run);
    }
}
