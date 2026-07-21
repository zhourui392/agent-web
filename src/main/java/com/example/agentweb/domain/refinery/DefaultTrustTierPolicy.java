package com.example.agentweb.domain.refinery;

/**
 * {@link TrustTierPolicy} 的默认实现. 规则表:
 *
 * <pre>
 * sourceType | verdict  | tier         | ingest
 * -----------+----------+--------------+--------
 * CHAT       | *        | EXPLORATORY  | yes
 * DIAGNOSE   | POSITIVE | VERIFIED     | yes
 * DIAGNOSE   | NONE     | PENDING      | yes
 * DIAGNOSE   | NEGATIVE | (n/a)        | NO     (入口拦截, 不入库)
 * </pre>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-02
 */
public class DefaultTrustTierPolicy implements TrustTierPolicy {

    @Override
    public TrustTier decide(SourceType sourceType, VerdictSignal verdict) {
        if (sourceType == SourceType.DIAGNOSE) {
            if (verdict == VerdictSignal.POSITIVE) {
                return TrustTier.VERIFIED;
            }
            return TrustTier.PENDING;
        }
        // CHAT (及未来非诊断源) 一律 EXPLORATORY
        return TrustTier.EXPLORATORY;
    }

    @Override
    public boolean shouldIngest(SourceType sourceType, VerdictSignal verdict) {
        return !(sourceType == SourceType.DIAGNOSE && verdict == VerdictSignal.NEGATIVE);
    }
}
