package com.example.agentweb.config.workbench;

import com.example.agentweb.domain.workbench.HighImpactOperationPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 高影响操作的默认 fail-closed 领域策略装配。
 *
 * @author alex
 * @since 2026-08-01
 */
@Configuration
public class WorkbenchOperationConfiguration {

    @Bean
    public HighImpactOperationPolicy highImpactOperationPolicy() {
        return HighImpactOperationPolicy.withAuthorizationTtl(
                Duration.ofMinutes(15));
    }
}
