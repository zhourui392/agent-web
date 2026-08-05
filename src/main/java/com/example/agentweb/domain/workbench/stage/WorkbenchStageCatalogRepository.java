package com.example.agentweb.domain.workbench.stage;

/**
 * Workbench Stage Catalog 聚合生命周期仓储。
 *
 * @author alex
 * @since 2026-08-05
 */
public interface WorkbenchStageCatalogRepository {

    WorkbenchStageCatalog find();

    void save(
            WorkbenchStageCatalog catalog, long expectedCatalogVersion,
            String changedDefinitionIdentifier,
            long expectedDefinitionVersion);
}
