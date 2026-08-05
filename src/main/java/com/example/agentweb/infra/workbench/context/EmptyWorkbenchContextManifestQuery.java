package com.example.agentweb.infra.workbench.context;

import com.example.agentweb.app.workbench.run.WorkbenchContextManifestQuery;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.WorkbenchStageRunPreparationPlan;
import com.example.agentweb.domain.workbench.context.WorkbenchContextManifest;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Objects;

/**
 * Global Context 尚无已发布文档时提供确定性的空清单。
 *
 * @author alex
 * @since 2026-08-05
 */
@Component
public final class EmptyWorkbenchContextManifestQuery
        implements WorkbenchContextManifestQuery {

    private static final String EMPTY_CONTEXT_CANONICAL_FORM =
            "workbench-context@1\nversion=0\ndocuments=[]";
    private static final String EMPTY_CONTEXT_HASH =
            CanonicalHashing.sha256(EMPTY_CONTEXT_CANONICAL_FORM);
    private static final String EMPTY_CONTEXT_PROMPT =
            "Context version: 0\nNo published documents.";

    @Override
    public WorkbenchContextManifest load(
            WorkbenchStageRunPreparationPlan plan) {
        WorkbenchStageRunPreparationPlan requiredPlan =
                Objects.requireNonNull(plan, "Stage Run preparation plan");
        return WorkbenchContextManifest.freeze(
                requiredPlan.getWorkbenchId(), 0L, EMPTY_CONTEXT_HASH,
                Collections.emptyList(), EMPTY_CONTEXT_PROMPT);
    }
}
