package com.example.agentweb.app.harness.port;

import java.time.Instant;

/**
 * Runtime 原始输出正文的受控存储端口；返回可审计但不泄露物理路径的逻辑引用。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface RuntimeEvidenceStore {

    /**
     * 保存已脱敏的 JSONL 正文。
     *
     * @param spec 执行规格
     * @param redactedJsonl 已脱敏 JSONL
     * @param createdAt 创建时间
     * @return 不包含物理路径的 Evidence Reference
     */
    String store(AgentExecutionSpec spec, byte[] redactedJsonl, Instant createdAt);
}
