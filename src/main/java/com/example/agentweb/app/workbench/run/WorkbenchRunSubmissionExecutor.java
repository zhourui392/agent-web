package com.example.agentweb.app.workbench.run;

import java.util.function.Supplier;

/**
 * 串行执行一次 Workbench Run 数据库提交事务。
 *
 * @author alex
 * @since 2026-08-01
 */
@FunctionalInterface
public interface WorkbenchRunSubmissionExecutor {

    WorkbenchRunSubmissionResult execute(
            Supplier<WorkbenchRunSubmissionResult> action);
}
