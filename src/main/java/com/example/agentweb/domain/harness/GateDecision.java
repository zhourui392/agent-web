package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 服务端确定性 Gate 的计算结果。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class GateDecision {

    private final boolean passed;
    private final String reason;
    private final List<String> evidenceReferences;

    private GateDecision(boolean passed, String reason, List<String> evidenceReferences) {
        this.passed = passed;
        this.reason = reason;
        this.evidenceReferences = Collections.unmodifiableList(
                new ArrayList<String>(evidenceReferences));
    }

    public static GateDecision pass(List<String> evidenceReferences) {
        return new GateDecision(true, null, evidenceReferences);
    }

    public static GateDecision fail(String reason, List<String> evidenceReferences) {
        return new GateDecision(false, DomainText.require(reason, "gate failure reason"),
                evidenceReferences);
    }
}
