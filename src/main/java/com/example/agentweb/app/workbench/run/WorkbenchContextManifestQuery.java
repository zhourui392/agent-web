package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.workbench.WorkbenchStageRunPreparationPlan;
import com.example.agentweb.domain.workbench.context.WorkbenchContextManifest;

/**
 * 为单次 Dynamic Stage Run 冻结全局上下文清单的读端口。
 *
 * @author alex
 * @since 2026-08-05
 */
public interface WorkbenchContextManifestQuery {

    WorkbenchContextManifest load(WorkbenchStageRunPreparationPlan plan);
}
