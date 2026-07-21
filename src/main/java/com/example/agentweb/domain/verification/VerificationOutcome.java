package com.example.agentweb.domain.verification;

/**
 * 验证终态（平台中性词汇）。dianxiaoer 词汇（SWIMLANE_VERIFIED / 退出码 66/67）到本枚举的
 * 翻译发生在适配器边界（防腐），域只见中性枚举。随 M0 状态机建包：T8/T9 签名依赖它。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public enum VerificationOutcome {

    VERIFIED,
    BLOCKED,
    DEPLOY_FAILED;

    /**
     * 工件缺失/解析失败时按 run 退出码兜底（detailed-design §4.3）：非 0 → DEPLOY_FAILED；
     * 0 但无 .flowstate 证据 → BLOCKED 交人工确认，绝不自动放行。
     *
     * @param exitCode run 进程退出码
     * @return 兜底终态
     */
    public static VerificationOutcome fallbackForExit(int exitCode) {
        return exitCode == 0 ? BLOCKED : DEPLOY_FAILED;
    }
}
