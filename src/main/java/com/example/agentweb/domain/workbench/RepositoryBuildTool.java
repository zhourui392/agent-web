package com.example.agentweb.domain.workbench;

/**
 * 根级可信 marker 能确定的构建或依赖管理入口。
 *
 * <p>枚举只表达 marker 可以证明的事实。例如 {@code package.json} 只能证明存在 Node
 * package 入口，不能凭空推断实际使用 npm、pnpm 或 yarn。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public enum RepositoryBuildTool {
    MAVEN,
    GRADLE,
    NODE_PACKAGE,
    PYPROJECT,
    PIP_REQUIREMENTS,
    GO_MODULES,
    CARGO
}
