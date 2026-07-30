package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ToolInvocation;
import com.example.agentweb.domain.chatrun.ToolInvocationRepository;
import com.example.agentweb.domain.chatrun.ToolInvocationSource;
import com.example.agentweb.domain.chatrun.ToolInvocationStatus;
import com.example.agentweb.domain.chatrun.ToolInvocationTriggerSource;
import com.example.agentweb.domain.shared.AgentType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class ChatToolInvocationTrackerFactory {

    private final ToolInvocationRepository repository;
    private final ToolInvocationEventExtractor extractor;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final int inputLimit;
    private final int outputLimit;

    public ChatToolInvocationTrackerFactory(ToolInvocationRepository repository,
                                            ToolInvocationEventExtractor extractor,
                                            ObjectMapper mapper,
                                            @Value("${agent.chat.tool-invocation.enabled:true}") boolean enabled,
                                            @Value("${agent.chat.tool-invocation.input-max-chars:65536}") int inputLimit,
                                            @Value("${agent.chat.tool-invocation.output-max-chars:65536}") int outputLimit) {
        this.repository = repository;
        this.extractor = extractor;
        this.mapper = mapper;
        this.enabled = enabled;
        this.inputLimit = inputLimit;
        this.outputLimit = outputLimit;
    }

    public Tracker open(String sessionId, String runId, AgentType provider) {
        return new Tracker(sessionId, runId, provider);
    }

    public final class Tracker {
        private final String sessionId;
        private final String runId;
        private final AgentType provider;
        private final Map<String, PendingInvocation> pending = new LinkedHashMap<String, PendingInvocation>();
        private int nextIndex;

        private Tracker(String sessionId, String runId, AgentType provider) {
            this.sessionId = sessionId;
            this.runId = runId;
            this.provider = provider;
        }

        public synchronized void accept(String rawLine) {
            if (!enabled) {
                return;
            }
            try {
                List<ToolInvocationEvent> events = extractor.extract(provider, rawLine);
                for (ToolInvocationEvent event : events) {
                    apply(event);
                }
            } catch (RuntimeException ex) {
                log.warn("tool-invocation-track-failed runId={} reason={}", runId, ex.getMessage());
            }
        }

        public synchronized void recordExplicitSkill(PreparedChatRunPrompt.ExplicitSkillInvocation skill) {
            if (!enabled || skill == null) {
                return;
            }
            long now = System.currentTimeMillis();
            try {
                String input = mapper.writeValueAsString(java.util.Collections.singletonMap(
                        "arguments", skill.getArguments()));
                repository.save(ToolInvocation.builder().sessionId(sessionId).runId(runId).provider(provider)
                        .invocationIndex(++nextIndex).invocationKind(
                                com.example.agentweb.domain.chatrun.ToolInvocationKind.SKILL)
                        .toolName("Skill").skillName(skill.getSkillName())
                        .triggerSource(ToolInvocationTriggerSource.USER_SLASH).inputJson(input)
                        .status(ToolInvocationStatus.STARTED).startedAt(now).createdAt(now).updatedAt(now)
                        .source(ToolInvocationSource.LIVE).build());
            } catch (Exception ex) {
                log.warn("explicit-skill-save-failed runId={} reason={}", runId, ex.getMessage());
            }
        }

        public synchronized void finish(Long assistantMessageId) {
            if (!enabled) {
                return;
            }
            long now = System.currentTimeMillis();
            for (PendingInvocation invocation : pending.values()) {
                if (!invocation.completed) {
                    invocation.status = ToolInvocationStatus.INCOMPLETE;
                    invocation.updatedAt = now;
                    safeSave(invocation);
                }
            }
            try {
                repository.completeExplicitSkills(runId, assistantMessageId == null
                        ? ToolInvocationStatus.INCOMPLETE : ToolInvocationStatus.SUCCEEDED);
            } catch (RuntimeException ex) {
                log.warn("explicit-skill-complete-failed runId={} reason={}", runId, ex.getMessage());
            }
            if (assistantMessageId != null) {
                try {
                    repository.attachAssistantMessage(runId, assistantMessageId.longValue());
                } catch (RuntimeException ex) {
                    log.warn("tool-invocation-attach-failed runId={} reason={}", runId, ex.getMessage());
                }
            }
        }

        private void apply(ToolInvocationEvent event) {
            if (event instanceof ToolInvocationEvent.Started) {
                ToolInvocationEvent.Started started = (ToolInvocationEvent.Started) event;
                PendingInvocation invocation = new PendingInvocation(started, ++nextIndex);
                pending.put(started.getCallId(), invocation);
                safeSave(invocation);
            } else if (event instanceof ToolInvocationEvent.InputDelta) {
                ToolInvocationEvent.InputDelta delta = (ToolInvocationEvent.InputDelta) event;
                PendingInvocation invocation = pending.get(delta.getCallId());
                if (invocation != null) {
                    invocation.input.append(delta.getPartialJson());
                }
            } else if (event instanceof ToolInvocationEvent.Completed) {
                ToolInvocationEvent.Completed completed = (ToolInvocationEvent.Completed) event;
                PendingInvocation invocation = pending.get(completed.getCallId());
                if (invocation != null) {
                    invocation.complete(completed);
                    safeSave(invocation);
                }
            }
        }

        private void safeSave(PendingInvocation invocation) {
            try {
                repository.save(invocation.toDomain());
            } catch (RuntimeException ex) {
                log.warn("tool-invocation-save-failed runId={} callId={} reason={}",
                        runId, invocation.callId, ex.getMessage());
            }
        }

        private final class PendingInvocation {
            private final String callId;
            private final com.example.agentweb.domain.chatrun.ToolInvocationKind kind;
            private final String toolName;
            private final String itemType;
            private final int index;
            private final long createdAt = System.currentTimeMillis();
            private final StringBuilder input = new StringBuilder();
            private String output;
            private boolean error;
            private Integer exitCode;
            private String providerStatus;
            private ToolInvocationStatus status = ToolInvocationStatus.STARTED;
            private long updatedAt = createdAt;
            private Long completedAt;
            private boolean completed;

            private PendingInvocation(ToolInvocationEvent.Started event, int index) {
                this.callId = event.getCallId();
                this.kind = event.getKind();
                this.toolName = event.getToolName();
                this.itemType = event.getItemType();
                this.index = index;
                if (event.getInitialInputJson() != null && !"{}".equals(event.getInitialInputJson())) {
                    input.append(event.getInitialInputJson());
                }
            }

            private void complete(ToolInvocationEvent.Completed event) {
                output = event.getOutputText();
                error = event.isError();
                exitCode = event.getExitCode();
                providerStatus = event.getProviderStatus();
                status = error ? ToolInvocationStatus.FAILED : ToolInvocationStatus.SUCCEEDED;
                completedAt = System.currentTimeMillis();
                updatedAt = completedAt.longValue();
                completed = true;
            }

            private ToolInvocation toDomain() {
                String rawInput = input.length() == 0 ? "{}" : input.toString();
                String skillName = kind == com.example.agentweb.domain.chatrun.ToolInvocationKind.SKILL
                        ? skillName(rawInput) : null;
                boolean inputTruncated = rawInput.length() > inputLimit;
                String safeInput = redactAndLimit(rawInput, inputLimit);
                int outputSize = output == null ? 0 : output.length();
                boolean outputTruncated = outputSize > outputLimit;
                String safeOutput = output == null ? null : limit(output, outputLimit);
                return ToolInvocation.builder().sessionId(sessionId).runId(runId).provider(provider)
                        .providerCallId(callId).invocationIndex(index).invocationKind(kind)
                        .toolName(toolName).skillName(skillName).triggerSource(ToolInvocationTriggerSource.AGENT)
                        .inputJson(safeInput).outputText(safeOutput).status(status).error(error).exitCode(exitCode)
                        .providerItemType(itemType).providerStatus(providerStatus).inputTruncated(inputTruncated)
                        .outputTruncated(outputTruncated).outputOriginalSize(output == null ? null : outputSize)
                        .startedAt(createdAt).completedAt(completedAt).createdAt(createdAt).updatedAt(updatedAt)
                        .source(ToolInvocationSource.LIVE).build();
            }
        }

        private String skillName(String inputJson) {
            try {
                return mapper.readTree(inputJson).path("skill").isTextual()
                        ? mapper.readTree(inputJson).path("skill").asText() : null;
            } catch (Exception ignored) {
                return null;
            }
        }

        private String redactAndLimit(String inputJson, int limit) {
            try {
                JsonNode root = mapper.readTree(inputJson);
                redact(root);
                return limit(mapper.writeValueAsString(root), limit);
            } catch (Exception ignored) {
                return limit(inputJson, limit);
            }
        }

        private void redact(JsonNode node) {
            if (node.isObject()) {
                java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String key = field.getKey().replace("_", "").toLowerCase();
                    if (key.matches(".*(password|token|secret|apikey|authorization|cookie|privatekey|credential).*$")) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put(field.getKey(), "[REDACTED]");
                    } else {
                        redact(field.getValue());
                    }
                }
            } else if (node.isArray()) {
                for (JsonNode child : node) {
                    redact(child);
                }
            }
        }

        private String limit(String value, int maximum) {
            return value.length() <= maximum ? value : value.substring(0, maximum);
        }
    }
}
