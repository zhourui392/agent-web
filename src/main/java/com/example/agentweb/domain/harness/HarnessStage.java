package com.example.agentweb.domain.harness;

/**
 * Harness 首版固定四阶段。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public enum HarnessStage {
    ANALYSIS,
    DESIGN,
    IMPLEMENTATION,
    DEPLOYMENT;

    /**
     * 是否存在前置阶段。
     *
     * @return 存在前置阶段时为 true
     */
    public boolean hasPrevious() {
        return ordinal() > 0;
    }

    /**
     * 返回固定顺序中的前置阶段。
     *
     * @return 前置阶段
     */
    public HarnessStage previous() {
        if (!hasPrevious()) {
            throw new IllegalStateException("analysis stage has no previous stage");
        }
        return values()[ordinal() - 1];
    }
}
