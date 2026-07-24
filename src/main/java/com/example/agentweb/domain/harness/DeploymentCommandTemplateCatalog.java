package com.example.agentweb.domain.harness;

/**
 * 管理员注册部署模板 Catalog。
 *
 * @author alex
 * @since 2026-07-23
 */
public interface DeploymentCommandTemplateCatalog {

    DeploymentCommandTemplate resolve(String templateId);
}
