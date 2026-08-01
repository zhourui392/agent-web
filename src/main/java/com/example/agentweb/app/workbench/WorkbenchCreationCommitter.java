package com.example.agentweb.app.workbench;

/**
 * 将准备完成的 Workbench 创建事实原子提交。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkbenchCreationCommitter {

    WorkbenchCreationResult commit(PreparedWorkbenchCreation creation);
}
