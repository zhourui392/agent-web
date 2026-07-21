package com.example.agentweb.interfaces;

import com.example.agentweb.app.delivery.DeliveryAppService;
import com.example.agentweb.app.delivery.MergeRequestStore;
import com.example.agentweb.app.requirement.FixRunService;
import com.example.agentweb.app.requirement.ImplementRunService;
import com.example.agentweb.app.requirement.PlanRunService;
import com.example.agentweb.app.requirement.RequirementRunLauncher;
import com.example.agentweb.app.requirement.RunEventBus;
import com.example.agentweb.app.requirement.VerifyRunService;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.delivery.MergeRequestRef;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求线 run 与交付端点（M2/M2.5）：发 run 一律 202（异步，进度看 SSE 与事件时间线）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@RestController
@RequestMapping(path = "/api/requirements", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "agent.requirement.enabled", havingValue = "true")
public class RequirementRunController {

    private static final long SSE_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final PlanRunService planRunService;
    private final ImplementRunService implementRunService;
    private final FixRunService fixRunService;
    private final VerifyRunService verifyRunService;
    private final DeliveryAppService deliveryAppService;
    private final MergeRequestStore mergeRequestStore;
    private final RunEventBus eventBus;
    private final CurrentUserProvider currentUserProvider;
    private final com.example.agentweb.domain.verification.VerificationRoundRepository roundRepository;

    public RequirementRunController(PlanRunService planRunService,
                                    ImplementRunService implementRunService,
                                    FixRunService fixRunService,
                                    VerifyRunService verifyRunService,
                                    DeliveryAppService deliveryAppService,
                                    MergeRequestStore mergeRequestStore,
                                    RunEventBus eventBus,
                                    CurrentUserProvider currentUserProvider,
                                    com.example.agentweb.domain.verification.VerificationRoundRepository roundRepository) {
        this.planRunService = planRunService;
        this.implementRunService = implementRunService;
        this.fixRunService = fixRunService;
        this.verifyRunService = verifyRunService;
        this.deliveryAppService = deliveryAppService;
        this.mergeRequestStore = mergeRequestStore;
        this.eventBus = eventBus;
        this.currentUserProvider = currentUserProvider;
        this.roundRepository = roundRepository;
    }

    /** 验证轮次视图（M4.5）：轮次号/verdict/证据引用，供看板抽屉轮次区消费。 */
    @GetMapping("/{id}/verification-rounds")
    public List<Map<String, Object>> verificationRounds(@PathVariable("id") String id) {
        return roundRepository.findByRequirementId(id).stream()
                .map(round -> {
                    Map<String, Object> view = new HashMap<>(8);
                    view.put("round", round.getRound());
                    view.put("verdict", round.getVerdict().name());
                    view.put("failedCount", round.getFailedCount());
                    view.put("evidenceRef", round.getEvidenceRef());
                    view.put("createdAt", round.getCreatedAt().toEpochMilli());
                    return view;
                })
                .toList();
    }

    @PostMapping("/{id}/plan-run")
    public ResponseEntity<Map<String, Object>> planRun(@PathVariable("id") String id) {
        planRunService.startPlanRun(id, actor());
        return accepted();
    }

    @PostMapping("/{id}/implement-run")
    public ResponseEntity<Map<String, Object>> implementRun(@PathVariable("id") String id) {
        implementRunService.startImplementRun(id, actor());
        return accepted();
    }

    @PostMapping("/{id}/fix-run")
    public ResponseEntity<Map<String, Object>> fixRun(@PathVariable("id") String id) {
        fixRunService.startFixRun(id, actor());
        return accepted();
    }

    @PostMapping("/{id}/verify-run")
    public ResponseEntity<Map<String, Object>> verifyRun(@PathVariable("id") String id) {
        verifyRunService.startVerifyRun(id, actor());
        return accepted();
    }

    @PostMapping("/{id}/deliver-draft")
    public Map<String, Object> deliverDraft(@PathVariable("id") String id) {
        MergeRequestRef mr = deliveryAppService.deliverDraft(id, actor());
        Map<String, Object> body = new HashMap<>(8);
        body.put("mrIid", mr.getMrIid());
        body.put("mrUrl", mr.getUrl());
        body.put("draft", mr.isDraft());
        return body;
    }

    @GetMapping("/{id}/merge-requests")
    public List<Map<String, Object>> mergeRequests(@PathVariable("id") String id) {
        return mergeRequestStore.findByRequirementId(id).stream()
                .map(this::toMrView)
                .toList();
    }

    /** run 输出 SSE 订阅（RunEventBus 广播，流 key = req-run-<id>）。 */
    @GetMapping(path = "/{id}/run-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runStream(@PathVariable("id") String id) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        eventBus.subscribe(RequirementRunLauncher.STREAM_KEY_PREFIX + id, emitter);
        return emitter;
    }

    private Map<String, Object> toMrView(MergeRequestRef mr) {
        Map<String, Object> view = new HashMap<>(8);
        view.put("mrIid", mr.getMrIid());
        view.put("mrUrl", mr.getUrl());
        view.put("draft", mr.isDraft());
        view.put("pipelineStatus", mr.getPipelineStatus());
        return view;
    }

    private ResponseEntity<Map<String, Object>> accepted() {
        Map<String, Object> body = new HashMap<>(4);
        body.put("accepted", true);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    private String actor() {
        return currentUserProvider.currentUserId();
    }
}
