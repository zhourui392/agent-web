package com.example.agentweb.app.workbench;

import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchCreationReceipt;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import lombok.Getter;

/**
 * 外部 Workspace 事实已准备完成、等待原子落库的创建数据。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PreparedWorkbenchCreation {

    private final Workbench workbench;
    private final WorkspaceSnapshot snapshot;
    private final WorkbenchCreationReceipt receipt;

    public PreparedWorkbenchCreation(
            Workbench workbench, WorkspaceSnapshot snapshot,
            WorkbenchCreationReceipt receipt) {
        if (workbench == null || snapshot == null || receipt == null) {
            throw new IllegalArgumentException(
                    "prepared workbench creation facts must not be null");
        }
        receipt.requirePreparedFacts(workbench, snapshot);
        this.workbench = workbench;
        this.snapshot = snapshot;
        this.receipt = receipt;
    }
}
