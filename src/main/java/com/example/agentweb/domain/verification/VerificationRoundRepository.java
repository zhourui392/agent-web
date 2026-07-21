package com.example.agentweb.domain.verification;

import java.util.List;

/**
 * 验证轮次仓储（domain 契约）：只追加不改写，轮次号由调用方按已有轮数递增。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface VerificationRoundRepository {

    void save(VerificationRound round);

    /** 按 round 升序返回该需求全部轮次。 */
    List<VerificationRound> findByRequirementId(String requirementId);
}
