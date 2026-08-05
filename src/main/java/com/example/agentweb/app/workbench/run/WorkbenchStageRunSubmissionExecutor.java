package com.example.agentweb.app.workbench.run;

import java.util.function.Supplier;

/**
 * 串行执行一次 Workbench Stage Run 数据库事务。
 *
 * @author alex
 * @since 2026-08-05
 */
@FunctionalInterface
public interface WorkbenchStageRunSubmissionExecutor {

    WorkbenchStageRunSubmissionResult execute(
            Supplier<WorkbenchStageRunSubmissionResult> action);
}
