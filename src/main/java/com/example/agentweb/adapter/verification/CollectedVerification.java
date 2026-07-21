package com.example.agentweb.adapter.verification;

import com.example.agentweb.domain.verification.VerificationOutcome;
import lombok.Value;

import java.util.List;

/**
 * 验证工件采集结果。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class CollectedVerification {

    /** 从 .flowstate 翻译出的终态;工件缺失/解析失败时为 null(app 按退出码兜底) */
    VerificationOutcome outcome;

    /** 采集到的工件(可为空列表) */
    List<CollectedArtifact> artifacts;

    /** 降级原因;正常解析时为 null */
    String degradeReason;

    public CollectedVerification(VerificationOutcome outcome, List<CollectedArtifact> artifacts,
                                 String degradeReason) {
        this.outcome = outcome;
        this.artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        this.degradeReason = degradeReason;
    }
}
