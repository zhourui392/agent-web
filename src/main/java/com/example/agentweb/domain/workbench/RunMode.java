package com.example.agentweb.domain.workbench;

/**
 * 单次 Workbench Run 对仓库的意图，不能隐式产生高影响操作授权。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum RunMode {
    DISCUSS_READ_ONLY(false),
    MODIFY_WORKSPACE(true);

    private final boolean modifiesWorkspace;

    RunMode(boolean modifiesWorkspace) {
        this.modifiesWorkspace = modifiesWorkspace;
    }

    public boolean modifiesWorkspace() {
        return modifiesWorkspace;
    }
}
