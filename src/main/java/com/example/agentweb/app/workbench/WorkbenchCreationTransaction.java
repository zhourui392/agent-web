package com.example.agentweb.app.workbench;

import java.util.function.Supplier;

/**
 * Workbench 创建的串行事务边界。
 *
 * @author alex
 * @since 2026-08-01
 */
@FunctionalInterface
public interface WorkbenchCreationTransaction {

    WorkbenchCreationResult execute(Supplier<WorkbenchCreationResult> action);
}
