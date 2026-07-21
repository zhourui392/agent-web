package com.example.agentweb.app.requirement;

import com.example.agentweb.adapter.UserDirectory;
import com.example.agentweb.domain.requirement.IntakeOwnerPolicy;
import com.example.agentweb.domain.requirement.RequirementSource;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;

/**
 * 外部系统建需求编排（detailed-design §3.7）：(apiKeyName, Idempotency-Key) 幂等 →
 * owner 回落链（域策略 + 用户目录软闸）→ 建需求（source=REST_API）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
public class ExternalIntakeService {

    private final RequirementAppService appService;
    private final RequirementIdempotencyStore idempotencyStore;
    private final RequirementProperties properties;
    private final UserDirectory userDirectory;
    private final IntakeOwnerPolicy ownerPolicy = new IntakeOwnerPolicy();

    public ExternalIntakeService(RequirementAppService appService,
                                 RequirementIdempotencyStore idempotencyStore,
                                 RequirementProperties properties,
                                 UserDirectory userDirectory) {
        this.appService = appService;
        this.idempotencyStore = idempotencyStore;
        this.properties = properties;
        this.userDirectory = userDirectory;
    }

    /**
     * 外部建需求。
     *
     * @param apiKeyName     鉴权过的 API Key 名（幂等键命名空间）
     * @param idempotencyKey Idempotency-Key 头，可空 = 不去重
     * @param request        请求载荷
     * @return 结果（duplicated=true 表示幂等命中既有需求）
     */
    public IntakeOutcome intake(String apiKeyName, String idempotencyKey, ExternalRequirementRequest request) {
        if (hasText(idempotencyKey)) {
            Optional<String> existing = idempotencyStore.findRequirementId(apiKeyName, idempotencyKey);
            if (existing.isPresent()) {
                log.info("req-external-idempotency-hit apiKey={} requirementId={}",
                        apiKeyName, existing.get());
                return new IntakeOutcome(existing.get(), true);
            }
        }
        String owner = ownerPolicy.resolveOwner(request.getOwner(),
                properties.getIntake().getDefaultOwner(), userDirectory::containsUser);
        String requirementId = appService.createWithRef(RequirementSource.REST_API,
                request.getDocUrl(), request.getTitle(), request.getDescription(), owner);
        if (hasText(idempotencyKey)) {
            idempotencyStore.record(apiKeyName, idempotencyKey, requirementId, Instant.now());
        }
        log.info("req-external-created apiKey={} requirementId={} owner={}",
                apiKeyName, requirementId, owner);
        return new IntakeOutcome(requirementId, false);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 外部建需求请求载荷。 */
    @Value
    public static class ExternalRequirementRequest {
        String title;
        String description;
        String docUrl;
        String owner;
    }

    /** 外部建需求结果。 */
    @Value
    public static class IntakeOutcome {
        String requirementId;
        boolean duplicated;
    }
}
