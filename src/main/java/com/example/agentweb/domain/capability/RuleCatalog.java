package com.example.agentweb.domain.capability;

/**
 * 版本化 Rule 定义发现端口。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface RuleCatalog {

    RuleDefinition resolveById(String logicalId);
}
