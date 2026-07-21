package com.example.agentweb.interfaces;

import com.example.agentweb.app.requirement.ExternalIntakeService;
import com.example.agentweb.infra.auth.ApiKeyAuthFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 外部系统建需求入口（X-API-Key 由 {@link ApiKeyAuthFilter} 前置校验，本路径已加入其保护清单；
 * Idempotency-Key 走统一幂等模式）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@RestController
@RequestMapping(path = "/api/requirements/external", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "agent.requirement.enabled", havingValue = "true")
public class RequirementIntakeController {

    private final ExternalIntakeService externalIntakeService;

    public RequirementIntakeController(ExternalIntakeService externalIntakeService) {
        this.externalIntakeService = externalIntakeService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestAttribute(name = ApiKeyAuthFilter.ATTR_API_KEY_NAME, required = false) String apiKeyName,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, String> req) {
        if (apiKeyName == null || apiKeyName.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "missing api key context"));
        }
        ExternalIntakeService.IntakeOutcome outcome = externalIntakeService.intake(apiKeyName,
                idempotencyKey, new ExternalIntakeService.ExternalRequirementRequest(
                        req.get("title"), req.get("description"), req.get("docUrl"), req.get("owner")));
        Map<String, Object> body = new HashMap<>(8);
        body.put("id", outcome.getRequirementId());
        body.put("duplicated", outcome.isDuplicated());
        return ResponseEntity.status(outcome.isDuplicated() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(body);
    }
}
