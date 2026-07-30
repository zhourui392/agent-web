package com.example.agentweb.interfaces;

import com.example.agentweb.app.agentrun.NativeDiagnosisReadinessQueryService;
import com.example.agentweb.app.agentrun.NativeDiagnosisReadinessView;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Protected management endpoint for secret-free NATIVE diagnosis readiness.
 *
 * @author alex
 * @since 2026-07-30
 */
@RestController
@RequestMapping(path = "/api/metrics/native-diagnosis",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class NativeDiagnosisReadinessController {

    private final Optional<NativeDiagnosisReadinessQueryService> queryService;

    public NativeDiagnosisReadinessController(
            Optional<NativeDiagnosisReadinessQueryService> queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/readiness")
    public ResponseEntity<List<NativeDiagnosisReadinessView>> readiness() {
        return ResponseEntity.ok(queryService
                .map(NativeDiagnosisReadinessQueryService::currentReadiness)
                .orElseGet(List::of));
    }
}
