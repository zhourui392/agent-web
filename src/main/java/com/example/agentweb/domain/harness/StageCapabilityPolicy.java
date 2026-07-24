package com.example.agentweb.domain.harness;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Stage Contract 的首版默认 Skill 选择扩展。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public final class StageCapabilityPolicy {

    private static final Map<HarnessStage, Set<String>> DEFAULTS;
    private static final Map<HarnessStage, CapabilityGrant> CONVERSATION_GRANTS;

    static {
        Map<HarnessStage, Set<String>> defaults = new EnumMap<HarnessStage, Set<String>>(HarnessStage.class);
        defaults.put(HarnessStage.ANALYSIS, Collections.singleton("domain-modeling-audit"));
        defaults.put(HarnessStage.DESIGN, Collections.singleton("java-ddd-design"));
        defaults.put(HarnessStage.IMPLEMENTATION, Collections.singleton("java-tdd"));
        defaults.put(HarnessStage.DEPLOYMENT, Collections.singleton("release-verification"));
        DEFAULTS = Collections.unmodifiableMap(defaults);

        Map<HarnessStage, CapabilityGrant> grants =
                new EnumMap<HarnessStage, CapabilityGrant>(HarnessStage.class);
        CapabilityGrant readOnly = new CapabilityGrant(Collections.singleton("workspace"),
                Collections.<String>emptySet(), Collections.<String>emptySet());
        grants.put(HarnessStage.ANALYSIS, readOnly);
        grants.put(HarnessStage.DESIGN, readOnly);
        grants.put(HarnessStage.IMPLEMENTATION, new CapabilityGrant(
                Collections.singleton("workspace"), Collections.singleton("workspace"),
                Collections.singleton("mvn-test")));
        grants.put(HarnessStage.DEPLOYMENT, new CapabilityGrant(
                Collections.singleton("workspace"), Collections.<String>emptySet(),
                Collections.singleton("mvn-verify")));
        CONVERSATION_GRANTS = Collections.unmodifiableMap(grants);
    }

    private StageCapabilityPolicy() {
    }

    public static Set<String> defaultsFor(HarnessStage stage) {
        if (stage == null) {
            throw new IllegalArgumentException("stage must not be null");
        }
        Set<String> defaults = DEFAULTS.get(stage);
        if (defaults == null) {
            throw new IllegalArgumentException("stage capability policy is missing: " + stage);
        }
        return defaults;
    }

    public static CapabilityGrant conversationGrant(HarnessStage stage) {
        if (stage == null) {
            throw new IllegalArgumentException("stage must not be null");
        }
        CapabilityGrant grant = CONVERSATION_GRANTS.get(stage);
        if (grant == null) {
            throw new IllegalArgumentException("conversation capability policy is missing: " + stage);
        }
        return grant;
    }

    /**
     * Stage Contract 对 M2 文件/命令能力的上限；显式 Grant 不能突破此上限。
     *
     * @param stage Stage
     * @param request Skill 能力请求
     * @return 阶段是否允许该能力进入有效集合
     */
    public static boolean permits(HarnessStage stage, CapabilityRequest request) {
        if (stage == null || request == null) {
            throw new IllegalArgumentException("stage and capability request must not be null");
        }
        if (request.getKind() == CapabilityKind.FILE) {
            return request.getAccess() == CapabilityAccess.READ
                    || stage == HarnessStage.IMPLEMENTATION
                    && request.getAccess() == CapabilityAccess.WRITE;
        }
        if (stage == HarnessStage.IMPLEMENTATION) {
            return "mvn-test".equals(request.getResource());
        }
        if (stage == HarnessStage.DEPLOYMENT) {
            return "mvn-verify".equals(request.getResource());
        }
        return false;
    }
}
