package com.example.agentweb.interfaces;

import com.example.agentweb.app.delivery.ScmWebhookAppService;
import com.example.agentweb.app.requirement.RequirementProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * SCM webhook 接入（detailed-design §3.5）：X-Gitlab-Token 常量时间比对（secret 未配置一律拒收，
 * fail-closed）→ 可选 CIDR 白名单 → 交 app 编排（UUID 幂等在 app）。鉴权通过后永远 2xx，
 * 避免 GitLab 对 5xx 的重试风暴。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
@RestController
@RequestMapping(path = "/api/scm/webhook", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "agent.requirement.enabled", havingValue = "true")
public class ScmWebhookController {

    private final ScmWebhookAppService webhookAppService;
    private final RequirementProperties properties;

    public ScmWebhookController(ScmWebhookAppService webhookAppService,
                                RequirementProperties properties) {
        this.webhookAppService = webhookAppService;
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestHeader(value = "X-Gitlab-Event", required = false) String eventType,
            @RequestHeader(value = "X-Gitlab-Event-UUID", required = false) String eventUuid,
            @RequestBody String rawBody,
            HttpServletRequest request) {
        String secret = properties.getDelivery().getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("scm-webhook-rejected reason=secret-not-configured clientIp={}",
                    request.getRemoteAddr());
            return status(HttpStatus.SERVICE_UNAVAILABLE, "webhook disabled");
        }
        if (token == null || token.isBlank()) {
            return status(HttpStatus.UNAUTHORIZED, "missing X-Gitlab-Token");
        }
        if (!constantTimeEquals(secret, token)) {
            log.warn("scm-webhook-rejected reason=bad-token clientIp={}", request.getRemoteAddr());
            return status(HttpStatus.FORBIDDEN, "invalid token");
        }
        if (!ipAllowed(request.getRemoteAddr())) {
            log.warn("scm-webhook-rejected reason=ip-not-allowed clientIp={}", request.getRemoteAddr());
            return status(HttpStatus.FORBIDDEN, "source not allowed");
        }
        webhookAppService.handle(eventUuid, eventType, rawBody);
        return ResponseEntity.ok(Map.of("received", true));
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /** CIDR 白名单：空 = 不限（仅 secret 校验）；仅支持 IPv4 段与精确串匹配，够覆盖内网 GitLab 源。 */
    private boolean ipAllowed(String remoteIp) {
        List<String> cidrs = properties.getDelivery().getWebhookAllowedCidrs();
        if (cidrs == null || cidrs.isEmpty()) {
            return true;
        }
        return cidrs.stream().anyMatch(cidr -> matches(cidr.trim(), remoteIp));
    }

    private boolean matches(String cidr, String remoteIp) {
        if (!cidr.contains("/")) {
            return cidr.equals(remoteIp);
        }
        long[] range = parseCidrV4(cidr);
        long ip = ipv4ToLong(remoteIp);
        return range != null && ip >= 0 && (ip & range[1]) == (range[0] & range[1]);
    }

    private long[] parseCidrV4(String cidr) {
        String[] parts = cidr.split("/", 2);
        long base = ipv4ToLong(parts[0]);
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (base < 0 || prefix < 0 || prefix > 32) {
            return null;
        }
        long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return new long[]{base, mask};
    }

    private long ipv4ToLong(String ip) {
        String[] octets = ip == null ? new String[0] : ip.split("\\.");
        if (octets.length != 4) {
            return -1;
        }
        long value = 0;
        for (String octet : octets) {
            try {
                int part = Integer.parseInt(octet);
                if (part < 0 || part > 255) {
                    return -1;
                }
                value = (value << 8) | part;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return value;
    }

    private ResponseEntity<Map<String, Object>> status(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
