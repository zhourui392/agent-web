package com.example.agentweb.interfaces;

import com.example.agentweb.app.chat.ChatAppService;
import com.example.agentweb.app.chat.ChatMessageView;
import com.example.agentweb.app.chat.ChatSessionQueryService;
import com.example.agentweb.app.chat.ChatSessionSummary;
import com.example.agentweb.app.chat.StartSessionCommand;
import com.example.agentweb.app.chat.TruncateResult;
import com.example.agentweb.app.agentrun.AgentCatalogService;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.Feedback;
import com.example.agentweb.domain.slashcommand.SlashCommand;
import com.example.agentweb.infra.http.ClientIpResolver;
import com.example.agentweb.config.EnvProperties;
import com.example.agentweb.infra.setting.RuntimeAgentSettings;
import com.example.agentweb.infra.log.MdcContext;
import com.example.agentweb.interfaces.dto.CommandDto;
import com.example.agentweb.interfaces.dto.AgentCatalogResponse;
import com.example.agentweb.interfaces.dto.EnvDto;
import com.example.agentweb.interfaces.dto.FeedbackRequest;
import com.example.agentweb.interfaces.dto.FeedbackResponse;
import com.example.agentweb.interfaces.dto.StartSessionRequest;
import com.example.agentweb.interfaces.dto.StartSessionResponse;
import com.example.agentweb.interfaces.dto.SuccessResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author zhourui(V33215020)
 */
@RestController
@RequestMapping(path = "/api/chat", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@Slf4j
public class ChatController {

    private final ChatAppService appService;
    private final ChatSessionQueryService sessionQueryService;
    private final EnvProperties envProperties;
    private final com.example.agentweb.domain.slashcommand.SlashCommandExpander commandExpander;
    private final RuntimeAgentSettings runtimeAgentSettings;
    private final AgentCatalogService agentCatalogService;
    private final com.example.agentweb.app.mode.ModeSwitchAppService modeSwitchAppService;

    public ChatController(ChatAppService appService, ChatSessionQueryService sessionQueryService,
                          EnvProperties envProperties,
                          com.example.agentweb.domain.slashcommand.SlashCommandExpander commandExpander,
                          RuntimeAgentSettings runtimeAgentSettings,
                          AgentCatalogService agentCatalogService,
                          com.example.agentweb.app.mode.ModeSwitchAppService modeSwitchAppService) {
        this.appService = appService;
        this.sessionQueryService = sessionQueryService;
        this.envProperties = envProperties;
        this.commandExpander = commandExpander;
        this.runtimeAgentSettings = runtimeAgentSettings;
        this.agentCatalogService = agentCatalogService;
        this.modeSwitchAppService = modeSwitchAppService;
    }

    @PostMapping("/session")
    public StartSessionResponse start(@Valid @RequestBody StartSessionRequest req, HttpServletRequest httpRequest) {
        String clientIp = ClientIpResolver.resolve(httpRequest);
        log.info("chat-session-create-request agentType={} workingDir={} env={} modeId={} clientIp={}",
                req.getAgentType(), req.getWorkingDir(), req.getEnv(), req.getModeId(), clientIp);
        StartSessionCommand command = new StartSessionCommand(
                req.getAgentType(), req.getWorkingDir(), req.getEnv(), req.getModeId());
        ChatSession s = appService.startSession(command, clientIp);
        MdcContext.putSessionId(s.getId());
        log.info("chat-session-created sessionId={} agentType={} workingDir={} env={} modeId={} clientIp={}",
                s.getId(), s.getAgentType(), s.getWorkingDir(), s.getEnv(), s.getModeId(), s.getClientIp());
        return new StartSessionResponse(s.getId(), s.getAgentType().name(), s.getWorkingDir(), s.getEnv());
    }

    /**
     * 单步同步模式切换：导出旧会话转录 + 创建绑定新模式的新会话。
     * 有活跃 run 时 409；Idempotency-Key 防重复提交。
     */
    @org.springframework.web.bind.annotation.PostMapping("/session/{id}/mode-switch")
    public java.util.Map<String, String> switchMode(
            @org.springframework.web.bind.annotation.PathVariable("id") String id,
            @org.springframework.web.bind.annotation.RequestBody ModeSwitchRequest req,
            @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key") String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()
                || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key header is required (1-128 chars)");
        }
        String targetModeId = req == null ? null : req.getTargetModeId();
        com.example.agentweb.app.mode.ModeSwitchAppService.ModeSwitchResult result =
                modeSwitchAppService.switchMode(id, targetModeId, idempotencyKey.trim());
        MdcContext.putSessionId(result.newSessionId());
        log.info("chat-mode-switched fromSession={} newSession={} handoffId={}",
                id, result.newSessionId(), result.handoffDocumentId());
        return java.util.Map.of(
                "newSessionId", result.newSessionId(),
                "handoffDocumentId", result.handoffDocumentId());
    }

    /** 模式切换请求体；targetModeId 为空表示切回默认模式。 */
    public static final class ModeSwitchRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("targetModeId")
        private String targetModeId;

        public String getTargetModeId() {
            return targetModeId;
        }

        public void setTargetModeId(String targetModeId) {
            this.targetModeId = targetModeId;
        }
    }

    @GetMapping("/session/{id}/commands")
    public List<CommandDto> listCommands(@PathVariable("id") String id) {
        return appService.listCommands(id).stream()
                .map(this::toCommandDto)
                .collect(java.util.stream.Collectors.toList());
    }

    @GetMapping("/commands")
    public List<CommandDto> listCommandsByDir(@RequestParam("workingDir") String workingDir) {
        return commandExpander.listCommands(workingDir).stream()
                .map(this::toCommandDto)
                .collect(java.util.stream.Collectors.toList());
    }

    private CommandDto toCommandDto(SlashCommand cmd) {
        return new CommandDto(cmd.getName(), cmd.getDescription(), cmd.getArgumentHint());
    }

    @GetMapping("/sessions")
    public List<ChatSessionSummary> listSessions(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        int offset = (page - 1) * size;
        return sessionQueryService.findSummaryPaged(offset, size);
    }

    @GetMapping("/session/{id}/messages")
    public List<ChatMessageView> getMessages(@PathVariable("id") String id) {
        List<ChatMessageView> messages = sessionQueryService.findMessageViews(id);
        if (messages == null) {
            throw new IllegalArgumentException("Session not found: " + id);
        }
        return messages;
    }

    @DeleteMapping("/session/{id}")
    public SuccessResponse deleteSession(@PathVariable("id") String id) {
        MdcContext.putSessionId(id);
        log.info("chat-session-delete sessionId={}", id);
        appService.deleteSession(id);
        return new SuccessResponse(true);
    }

    @DeleteMapping("/session/{id}/messages")
    public TruncateResult truncateMessages(@PathVariable("id") String id,
                                           @RequestParam("fromId") long fromId) {
        return appService.truncateFrom(id, fromId);
    }

    @GetMapping("/envs")
    public List<EnvDto> listEnvs() {
        List<EnvDto> result = new ArrayList<EnvDto>();
        for (EnvProperties.EnvEntry e : envProperties.getEnvs()) {
            result.add(new EnvDto(e.getKey(), e.getLabel(), e.getColor()));
        }
        return result;
    }

    /**
     * 当前对话默认 agent 及其版本号,供前端"强制全员跟随"判定:版本与本地记录不一致时,
     * 前端覆盖本地选择并切到该 agent(管理后台改默认模型即变更版本)。
     *
     * @return {@code {agentType, version}}
     */
    @GetMapping("/agent-default")
    public Map<String, Object> agentDefault() {
        Map<String, Object> body = new java.util.HashMap<>(2);
        body.put("agentType", runtimeAgentSettings.getChatDefaultAgent().name());
        body.put("version", runtimeAgentSettings.getChatDefaultAgentVersion());
        return body;
    }

    /**
     * Effective user-facing agent catalog.
     *
     * @return versioned default and all known offers
     */
    @GetMapping("/agents")
    public AgentCatalogResponse agents() {
        return AgentCatalogResponse.from(agentCatalogService.currentCatalog(),
                runtimeAgentSettings.getChatDefaultAgent().name(),
                runtimeAgentSettings.getChatDefaultAgentVersion());
    }

    @PutMapping("/session/{id}/feedback")
    public FeedbackResponse saveFeedback(@PathVariable("id") String id, @RequestBody FeedbackRequest req) {
        MdcContext.putSessionId(id);
        Feedback feedback = appService.saveFeedback(id, req.getRating(), req.getComment());
        log.info("chat-feedback-request sessionId={} rating={}", id, feedback.getRating());
        return FeedbackResponse.from(feedback);
    }

    @GetMapping("/session/{id}/feedback")
    public FeedbackResponse getFeedback(@PathVariable("id") String id) {
        return FeedbackResponse.from(appService.getFeedback(id));
    }
}
