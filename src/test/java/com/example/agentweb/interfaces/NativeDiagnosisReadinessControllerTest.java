package com.example.agentweb.interfaces;

import com.example.agentweb.app.agentrun.NativeDiagnosisReadinessQueryService;
import com.example.agentweb.app.agentrun.NativeDiagnosisReadinessView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NATIVE readiness management endpoint projection tests.
 *
 * @author alex
 * @since 2026-07-30
 */
class NativeDiagnosisReadinessControllerTest {

    @Test
    void readiness_shouldReturnEmptyWhenNativeRuntimeIsDisabled() {
        NativeDiagnosisReadinessController controller =
                new NativeDiagnosisReadinessController(Optional.empty());

        assertThat(controller.readiness().getBody()).isEmpty();
    }

    @Test
    void readiness_shouldReturnSecretFreeRuntimeProjection() {
        NativeDiagnosisReadinessView view = new NativeDiagnosisReadinessView(
                "test", "CONFIGURED", "OPERATIONAL", "READY", "", List.of());
        NativeDiagnosisReadinessQueryService query = () -> List.of(view);
        NativeDiagnosisReadinessController controller =
                new NativeDiagnosisReadinessController(Optional.of(query));

        assertThat(controller.readiness().getBody()).containsExactly(view);
        assertThat(controller.readiness().getBody().toString())
                .doesNotContain("api-key", "authorization", "password");
    }
}
