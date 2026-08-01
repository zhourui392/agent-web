package com.example.agentweb.domain.workbench;

import java.util.Optional;

/**
 * Workbench 固定四阶段；顺序只用于导航和默认 Handoff 来源，不形成自动 Gate。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum WorkbenchPhase {
    REQUIREMENT_ANALYSIS,
    SOLUTION_DESIGN,
    IMPLEMENT_TEST,
    REVIEW_REFACTOR;

    public Optional<WorkbenchPhase> defaultHandoffSource() {
        switch (this) {
            case SOLUTION_DESIGN:
                return Optional.of(REQUIREMENT_ANALYSIS);
            case IMPLEMENT_TEST:
                return Optional.of(SOLUTION_DESIGN);
            case REVIEW_REFACTOR:
                return Optional.of(IMPLEMENT_TEST);
            case REQUIREMENT_ANALYSIS:
            default:
                return Optional.empty();
        }
    }
}
