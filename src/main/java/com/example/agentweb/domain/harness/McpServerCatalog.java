package com.example.agentweb.domain.harness;

import java.util.List;

/**
 * 可信 MCP Server Catalog 领域端口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface McpServerCatalog {

    /**
     * 发现管理员维护的可信 MCP Server 定义。
     *
     * @return 不可变定义列表
     */
    List<McpServerDefinition> discover();
}
