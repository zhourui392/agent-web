package com.example.agentweb.domain.harness;

/**
 * Prompt Pack 发现端口，文件读取实现位于 Infrastructure。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface PromptPackCatalog {

    PromptPack resolve(HarnessStage stage);
}
