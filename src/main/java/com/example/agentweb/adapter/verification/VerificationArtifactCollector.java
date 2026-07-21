package com.example.agentweb.adapter.verification;

/**
 * L1 验证工件采集端口:从 worktree 收集 .flowstate / failed_cases / verification-record,
 * 并把 dianxiaoer 词汇(SWIMLANE_VERIFIED 等)翻译成平台中性 VerificationOutcome(防腐在实现侧)。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface VerificationArtifactCollector {

    /**
     * 采集验证工件。工件缺失/解析失败不抛异常——返回 outcome=null + degradeReason,
     * 由 app 编排按 run 退出码兜底。
     *
     * @param worktreePath 需求 worktree 根路径
     * @return 采集结果(永不为 null)
     */
    CollectedVerification collect(String worktreePath);
}
