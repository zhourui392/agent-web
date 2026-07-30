package com.example.agentweb.app.agentrun;

import java.util.List;

/**
 * Read-side port for NATIVE diagnosis readiness management.
 *
 * @author alex
 * @since 2026-07-30
 */
public interface NativeDiagnosisReadinessQueryService {

    List<NativeDiagnosisReadinessView> currentReadiness();
}
