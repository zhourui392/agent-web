package com.example.agentweb.interfaces;

import com.example.agentweb.app.ChatAppService;
import com.example.agentweb.domain.ChatMessage;
import com.example.agentweb.domain.ChatSession;
import com.example.agentweb.domain.SessionRepository;
import com.example.agentweb.domain.SlashCommand;
import com.example.agentweb.infra.EnvProperties;
import com.example.agentweb.interfaces.dto.CommandDto;
import com.example.agentweb.interfaces.dto.EnvDto;
import com.example.agentweb.interfaces.dto.MessageDto;
import com.example.agentweb.interfaces.dto.SendMessageRequest;
import com.example.agentweb.interfaces.dto.SendMessageResponse;
import com.example.agentweb.interfaces.dto.SessionStatusResponse;
import com.example.agentweb.interfaces.dto.StartSessionRequest;
import com.example.agentweb.interfaces.dto.StartSessionResponse;
import com.example.agentweb.interfaces.dto.SuccessResponse;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(path = "/api/chat", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class ChatController {

    private final ChatAppService appService;
    private final SessionRepository sessionRepository;
    private final EnvProperties envProperties;
    private final com.example.agentweb.domain.SlashCommandExpander commandExpander;

    public ChatController(ChatAppService appService, SessionRepository sessionRepository, EnvProperties envProperties,
                          com.example.agentweb.domain.SlashCommandExpander commandExpander) {
        this.appService = appService;
        this.sessionRepository = sessionRepository;
        this.envProperties = envProperties;
        this.commandExpander = commandExpander;
    }

    @PostMapping("/session")
    public StartSessionResponse start(@Valid @RequestBody StartSessionRequest req) {
        ChatSession s = appService.startSession(req);
        return new StartSessionResponse(s.getId(), s.getAgentType().name(), s.getWorkingDir());
    }

    @PostMapping("/session/{id}/message")
    public SendMessageResponse send(@PathVariable("id") String id, @Valid @RequestBody SendMessageRequest req) throws IOException, InterruptedException {
        String out = appService.sendMessage(id, req);
        return new SendMessageResponse(out);
    }

    @GetMapping(value = "/session/{id}/message/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@PathVariable("id") String id,
                            @RequestParam("message") String message,
                            @RequestParam(value = "resumeId", required = false) String resumeId,
                            @RequestParam(value = "env", required = false) String env) {
        return appService.streamMessage(id, message, resumeId, env);
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
    public List<Map<String, Object>> listSessions(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        int offset = (page - 1) * size;
        return sessionRepository.findSummaryPaged(offset, size);
    }

    @GetMapping("/session/{id}/messages")
    public List<MessageDto> getMessages(@PathVariable("id") String id) {
        ChatSession s = sessionRepository.findById(id);
        if (s == null) {
            throw new IllegalArgumentException("Session not found: " + id);
        }
        List<MessageDto> result = new ArrayList<MessageDto>();
        for (ChatMessage msg : s.getMessages()) {
            result.add(new MessageDto(msg.getRole(), msg.getContent(), msg.getTimestamp().toString()));
        }
        return result;
    }

    @DeleteMapping("/session/{id}")
    public SuccessResponse deleteSession(@PathVariable("id") String id) {
        sessionRepository.deleteById(id);
        return new SuccessResponse(true);
    }

    @PostMapping("/session/{id}/summarize")
    public Map<String, Object> summarize(@PathVariable("id") String id) throws java.io.IOException, InterruptedException {
        return appService.summarizeSession(id);
    }

    @GetMapping("/envs")
    public List<EnvDto> listEnvs() {
        List<EnvDto> result = new ArrayList<EnvDto>();
        for (EnvProperties.EnvEntry e : envProperties.getEnvs()) {
            result.add(new EnvDto(e.getKey(), e.getLabel(), e.getColor()));
        }
        return result;
    }

    @GetMapping("/session/{id}/status")
    public SessionStatusResponse sessionStatus(@PathVariable("id") String id) {
        return new SessionStatusResponse(appService.isSessionRunning(id));
    }

    @PostMapping("/session/{id}/stop")
    public SuccessResponse stop(@PathVariable("id") String id) {
        appService.stopSession(id);
        return new SuccessResponse(true);
    }
}
