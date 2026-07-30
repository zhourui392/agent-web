package com.example.agentweb.app.agentrun;

import java.util.List;
import java.util.Set;

/**
 * Secret-free management projection of one NATIVE diagnosis environment.
 *
 * @author alex
 * @since 2026-07-30
 */
public record NativeDiagnosisReadinessView(
        String environment,
        String modelStatus,
        String diagnosisMode,
        String overallStatus,
        String reasonCode,
        List<Capability> capabilities) {

    public NativeDiagnosisReadinessView {
        capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
    }

    /** One logical tool/data-source readiness entry. */
    public record Capability(String toolName, String dataSourceId, String environment,
                             String readiness, Set<String> operations, String reasonCode) {
        public Capability {
            operations = Set.copyOf(operations == null ? Set.of() : operations);
        }
    }
}
