package com.example.agentweb.infra.cli;

import com.example.agentweb.app.chatrun.ToolInvocationEvent;
import com.example.agentweb.app.chatrun.ToolInvocationEventExtractor;
import com.example.agentweb.domain.chatrun.ToolInvocationKind;
import com.example.agentweb.domain.shared.AgentType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class JsonToolInvocationEventExtractor implements ToolInvocationEventExtractor {

    private final ObjectMapper mapper;
    private final ThreadLocal<java.util.Map<Integer, String>> claudeCallsByBlock =
            new ThreadLocal<java.util.Map<Integer, String>>() {
                @Override
                protected java.util.Map<Integer, String> initialValue() {
                    return new java.util.HashMap<Integer, String>();
                }
            };

    public JsonToolInvocationEventExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ToolInvocationEvent> extract(AgentType provider, String rawLine) {
        try {
            JsonNode root = mapper.readTree(rawLine);
            return provider == AgentType.CODEX ? codex(root) : claude(root);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<ToolInvocationEvent> claude(JsonNode root) throws Exception {
        List<ToolInvocationEvent> events = new ArrayList<ToolInvocationEvent>();
        if ("stream_event".equals(root.path("type").asText())) {
            JsonNode event = root.path("event");
            if ("content_block_start".equals(event.path("type").asText())
                    && "tool_use".equals(event.path("content_block").path("type").asText())) {
                JsonNode block = event.path("content_block");
                String id = block.path("id").asText();
                String name = block.path("name").asText();
                claudeCallsByBlock.get().put(event.path("index").asInt(-1), id);
                ToolInvocationKind kind = "Skill".equals(name)
                        ? ToolInvocationKind.SKILL : ToolInvocationKind.TOOL_USE;
                events.add(new ToolInvocationEvent.Started(id, kind, name, "tool_use",
                        initialInputJson(block)));
            } else if ("content_block_delta".equals(event.path("type").asText())
                    && "input_json_delta".equals(event.path("delta").path("type").asText())) {
                String id = event.path("delta").path("tool_use_id").asText("");
                if (id.isBlank()) {
                    id = claudeCallsByBlock.get().get(event.path("index").asInt(-1));
                }
                if (id != null) {
                    events.add(new ToolInvocationEvent.InputDelta(id,
                            event.path("delta").path("partial_json").asText("")));
                }
            }
        } else if ("user".equals(root.path("type").asText())) {
            for (JsonNode block : root.path("message").path("content")) {
                if ("tool_result".equals(block.path("type").asText())) {
                    JsonNode content = block.path("content");
                    String output = content.isTextual() ? content.asText() : mapper.writeValueAsString(content);
                    events.add(new ToolInvocationEvent.Completed(block.path("tool_use_id").asText(), output,
                            block.path("is_error").asBoolean(false), null, null));
                }
            }
        }
        return events;
    }

    private String initialInputJson(JsonNode block) throws Exception {
        JsonNode input = block.get("input");
        return input == null || input.isNull() ? "{}" : mapper.writeValueAsString(input);
    }

    private List<ToolInvocationEvent> codex(JsonNode root) throws Exception {
        String type = root.path("type").asText();
        JsonNode item = root.path("item");
        if (!"command_execution".equals(item.path("type").asText())) {
            return Collections.emptyList();
        }
        String id = item.path("id").asText();
        if ("item.started".equals(type)) {
            String input = mapper.writeValueAsString(Collections.singletonMap("command",
                    item.path("command").asText("")));
            return Collections.<ToolInvocationEvent>singletonList(new ToolInvocationEvent.Started(id,
                    ToolInvocationKind.COMMAND_EXECUTION, null, "command_execution", input));
        }
        if ("item.completed".equals(type)) {
            Integer exitCode = item.hasNonNull("exit_code") ? item.path("exit_code").asInt() : null;
            String status = item.hasNonNull("status") ? item.path("status").asText() : null;
            boolean error = (exitCode != null && exitCode.intValue() != 0) || "failed".equals(status);
            return Collections.<ToolInvocationEvent>singletonList(new ToolInvocationEvent.Completed(id,
                    item.path("aggregated_output").asText(""), error, exitCode, status));
        }
        return Collections.emptyList();
    }
}
