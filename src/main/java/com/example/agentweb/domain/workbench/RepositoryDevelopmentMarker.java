package com.example.agentweb.domain.workbench;

import lombok.Getter;

/**
 * Repository Scope 内允许探测的有限根级开发 marker Catalog。
 *
 * <p>Catalog 是显式 allowlist，不包含 {@code AGENTS.md}、{@code CLAUDE.md} 或任意用户路径。
 * 具体文件系统存在性和 symlink 安全检查由 Infrastructure 完成。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public enum RepositoryDevelopmentMarker {

    POM_XML("pom.xml", RepositoryTechnologyType.JAVA,
            RepositoryBuildTool.MAVEN, null),
    BUILD_GRADLE("build.gradle", RepositoryTechnologyType.JAVA,
            RepositoryBuildTool.GRADLE, null),
    BUILD_GRADLE_KOTLIN("build.gradle.kts", RepositoryTechnologyType.JAVA,
            RepositoryBuildTool.GRADLE, null),
    PACKAGE_JSON("package.json", RepositoryTechnologyType.NODE_JS,
            RepositoryBuildTool.NODE_PACKAGE, null),
    PYPROJECT_TOML("pyproject.toml", RepositoryTechnologyType.PYTHON,
            RepositoryBuildTool.PYPROJECT, null),
    REQUIREMENTS_TEXT("requirements.txt", RepositoryTechnologyType.PYTHON,
            RepositoryBuildTool.PIP_REQUIREMENTS, null),
    GO_MOD("go.mod", RepositoryTechnologyType.GO,
            RepositoryBuildTool.GO_MODULES, null),
    CARGO_TOML("Cargo.toml", RepositoryTechnologyType.RUST,
            RepositoryBuildTool.CARGO, null),
    README_MARKDOWN("README.md", null, null,
            RepositoryInstructionType.OVERVIEW),
    CONTRIBUTING_MARKDOWN("CONTRIBUTING.md", null, null,
            RepositoryInstructionType.CONTRIBUTING_GUIDE),
    DEVELOPMENT_MARKDOWN("DEVELOPMENT.md", null, null,
            RepositoryInstructionType.DEVELOPMENT_GUIDE);

    private final String relativePath;
    private final RepositoryTechnologyType technologyType;
    private final RepositoryBuildTool buildTool;
    private final RepositoryInstructionType instructionType;

    RepositoryDevelopmentMarker(String relativePath,
                                RepositoryTechnologyType technologyType,
                                RepositoryBuildTool buildTool,
                                RepositoryInstructionType instructionType) {
        this.relativePath = DocumentReference.requireRelativePath(relativePath);
        this.technologyType = technologyType;
        this.buildTool = buildTool;
        this.instructionType = instructionType;
    }

    public boolean isInstructionReference() {
        return instructionType != null;
    }
}
