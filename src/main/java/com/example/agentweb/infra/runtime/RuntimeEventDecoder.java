package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.RuntimeEvent;
import com.example.agentweb.app.runtime.port.RuntimeEventType;
import com.example.agentweb.app.runtime.port.RuntimeSemanticEvent;
import com.example.agentweb.app.runtime.port.WorkspaceLayout;
import com.example.agentweb.domain.runtime.RuntimeCommandAssessment;
import com.example.agentweb.domain.runtime.RuntimeCommandClass;
import com.example.agentweb.domain.runtime.RuntimeCommandPolicy;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.infra.cli.CodexEventNormalizer;
import com.example.agentweb.infra.cli.CliDialect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 将 Provider JSONL 输出解码为已脱敏、有界的公共 Runtime Event。
 *
 * <p>事件识别委托给 {@link CodexEventNormalizer}（与 Chat 路径复用同一套归一化逻辑），
 * 未识别事件直接跳过（返回 {@link DecodedEvent#skipped()}），不产生 RuntimeEvent。
 * Workbench 特有的 {@code file_change} / {@code mcp_tool_call} 语义在识别后额外提取。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public final class RuntimeEventDecoder {

    private static final String TURN_FAILED = "turn.failed";
    private static final String ITEM_STARTED = "item.started";
    private static final String ITEM_COMPLETED = "item.completed";
    private static final String MCP_TOOL_CALL = "mcp_tool_call";
    private static final String FILE_CHANGE = "file_change";
    private static final String BLOCK_REASON =
            "HIGH_IMPACT_OPERATION_REQUIRES_AUTHORIZATION";
    private static final String BLOCK_SUMMARY =
            "高影响操作未获得类型化授权，Runtime 已阻止执行";
    private static final Pattern SAFE_EVENT_TYPE =
            Pattern.compile("[A-Za-z0-9_.-]{1,80}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RuntimeOutputRedactor outputRedactor;
    private final RuntimeCommandPolicy commandPolicy;
    private final CodexEventNormalizer codexNormalizer;
    private final Map<String, Map<String, String>> claudeToolNamesByExecution =
            new ConcurrentHashMap<String, Map<String, String>>();
    private final Map<String, Set<String>> claudeToolInputsByExecution =
            new ConcurrentHashMap<String, Set<String>>();
    private final Map<String, Map<Integer, String>> claudeToolIdsByBlockExecution =
            new ConcurrentHashMap<String, Map<Integer, String>>();
    private final Map<String, Map<String, StringBuilder>> claudeToolInputFragmentsByExecution =
            new ConcurrentHashMap<String, Map<String, StringBuilder>>();

    public RuntimeEventDecoder(RuntimeOutputRedactor outputRedactor) {
        this(outputRedactor, RuntimeCommandPolicy.platformDefault(),
                new CodexEventNormalizer());
    }

    public RuntimeEventDecoder(RuntimeOutputRedactor outputRedactor,
                               RuntimeCommandPolicy commandPolicy) {
        this(outputRedactor, commandPolicy, new CodexEventNormalizer());
    }

    public RuntimeEventDecoder(RuntimeOutputRedactor outputRedactor,
                               RuntimeCommandPolicy commandPolicy,
                               CodexEventNormalizer codexNormalizer) {
        this.outputRedactor = Objects.requireNonNull(
                outputRedactor, "outputRedactor");
        this.commandPolicy = Objects.requireNonNull(
                commandPolicy, "commandPolicy");
        this.codexNormalizer = Objects.requireNonNull(
                codexNormalizer, "codexNormalizer");
    }

    public DecodedEvent decode(String executionId, long sequence,
                               String providerLine) {
        return decode(executionId, sequence, providerLine, null, null);
    }

    public DecodedEvent decode(
            String executionId, long sequence, String providerLine,
            RuntimeCapabilityMaterialization capabilities) {
        return decode(executionId, sequence, providerLine, capabilities, null);
    }

    public DecodedEvent decode(
            String executionId, long sequence, String providerLine,
            RuntimeCapabilityMaterialization capabilities,
            WorkspaceLayout workspaceLayout) {
        return decode(executionId, sequence, providerLine, capabilities,
                workspaceLayout, null);
    }

    /** Decode using the selected CLI dialect; non-Codex CLI output is projected generically. */
    public DecodedEvent decode(
            String executionId, long sequence, String providerLine,
            RuntimeCapabilityMaterialization capabilities,
            WorkspaceLayout workspaceLayout, CliDialect dialect) {
        Objects.requireNonNull(providerLine, "providerLine");
        if (dialect != null && dialect.type() != com.example.agentweb.domain.shared.AgentType.CODEX) {
            List<String> normalized = dialect.normalizeChunk(providerLine);
            if (normalized == null || normalized.isEmpty()) {
                return DecodedEvent.skipped();
            }
            JsonNode root = parseObject(providerLine);
            String type = providerEventType(root);
            RuntimeEventType eventType = "result".equals(type)
                    && root != null && "error".equals(root.path("subtype").asText())
                    ? RuntimeEventType.DIAGNOSTIC : RuntimeEventType.OUTPUT;
            String assistantText = claudeAssistantText(root);
            List<RuntimeSemanticEvent> semanticEvents = claudeSemantics(
                    executionId, root, capabilities);
            if ("result".equals(type)) {
                claudeToolNamesByExecution.remove(executionId);
                claudeToolInputsByExecution.remove(executionId);
                claudeToolIdsByBlockExecution.remove(executionId);
                claudeToolInputFragmentsByExecution.remove(executionId);
            }
            return new DecodedEvent(new RuntimeEvent(
                    executionId, sequence, eventType,
                    type.isEmpty() ? "cli output" : type, assistantText,
                    semanticEvents), type, eventType == RuntimeEventType.DIAGNOSTIC, false);
        }
        JsonNode root = parseObject(providerLine);
        String providerEventType = providerEventType(root);

        boolean chatRecognized = !codexNormalizer.normalize(providerLine)
                .isEmpty();
        boolean workbenchRecognized = !chatRecognized
                && isWorkbenchSpecificEvent(root, providerEventType);
        if (!chatRecognized && !workbenchRecognized) {
            return DecodedEvent.skipped();
        }

        boolean turnFailed = TURN_FAILED.equals(providerEventType);
        RuntimeEventType eventType = turnFailed
                ? RuntimeEventType.DIAGNOSTIC : RuntimeEventType.OUTPUT;
        String assistantText = normalizedAssistantText(
                root, providerEventType, capabilities);
        SemanticProjection projection = eventType == RuntimeEventType.OUTPUT
                ? semantics(root, providerEventType,
                capabilities, workspaceLayout)
                : SemanticProjection.empty();
        String safePayload = providerEventType.isEmpty()
                ? "unstructured provider output suppressed"
                : providerEventType;
        return new DecodedEvent(new RuntimeEvent(
                executionId, sequence, eventType, safePayload,
                assistantText, projection.getEvents()),
                providerEventType, turnFailed,
                projection.isOperationBlocked());
    }

    private String claudeAssistantText(JsonNode root) {
        if (root == null) {
            return null;
        }
        if ("assistant".equals(root.path("type").asText())) {
            JsonNode content = root.path("message").path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())
                            && !block.path("text").asText().isBlank()) {
                        return outputRedactor.boundEvidenceLine(
                                block.path("text").asText(), RuntimeEvent.MAX_SAFE_PAYLOAD_LENGTH);
                    }
                }
            }
        }
        if ("stream_event".equals(root.path("type").asText())
                && "text_delta".equals(root.path("event").path("delta").path("type").asText())) {
            String text = root.path("event").path("delta").path("text").asText("");
            return text.isBlank() ? null : outputRedactor.boundEvidenceLine(
                    text, RuntimeEvent.MAX_SAFE_PAYLOAD_LENGTH);
        }
        return null;
    }

    private List<RuntimeSemanticEvent> claudeSemantics(
            String executionId, JsonNode root,
            RuntimeCapabilityMaterialization capabilities) {
        if (root == null) {
            return Collections.emptyList();
        }
        if ("stream_event".equals(root.path("type").asText())) {
            JsonNode event = root.path("event");
            if ("content_block_start".equals(event.path("type").asText())
                    && "tool_use".equals(event.path("content_block").path("type").asText())) {
                rememberClaudeToolBlock(executionId, event);
                return claudeToolStarted(executionId, event.path("content_block"), capabilities);
            }
            if ("content_block_delta".equals(event.path("type").asText())
                    && "input_json_delta".equals(event.path("delta").path("type").asText())) {
                return claudeToolInputDelta(executionId, event, capabilities);
            }
            if (!"content_block_start".equals(event.path("type").asText())
                    || !"tool_use".equals(event.path("content_block").path("type").asText())) {
                return Collections.emptyList();
            }
        }
        if ("assistant".equals(root.path("type").asText())) {
            return claudeAssistantToolStarts(executionId, root.path("message").path("content"),
                    capabilities);
        }
        if (!"user".equals(root.path("type").asText())
                || !root.path("message").path("content").isArray()) {
            return Collections.emptyList();
        }
        List<RuntimeSemanticEvent> events = new ArrayList<RuntimeSemanticEvent>();
        for (JsonNode block : root.path("message").path("content")) {
            if (!"tool_result".equals(block.path("type").asText())) {
                continue;
            }
            String callId = identifier(block.get("tool_use_id"));
            if (callId == null) {
                continue;
            }
            String tool = claudeToolName(executionId, callId);
            JsonNode content = block.get("content");
            if ((content == null || content.isNull())
                    && root.path("tool_use_result").isTextual()) {
                content = root.path("tool_use_result");
            }
            String output = safeClaudeResult(content, capabilities);
            String status = block.path("is_error").asBoolean(false)
                    ? "FAILED" : "SUCCEEDED";
            events.add(RuntimeSemanticEvent.toolFinished(
                    tool, callId, status, output));
        }
        return events;
    }

    private List<RuntimeSemanticEvent> claudeAssistantToolStarts(
            String executionId, JsonNode content,
            RuntimeCapabilityMaterialization capabilities) {
        if (!content.isArray()) {
            return Collections.emptyList();
        }
        List<RuntimeSemanticEvent> events = new ArrayList<RuntimeSemanticEvent>();
        for (JsonNode block : content) {
            if ("tool_use".equals(block.path("type").asText())) {
                RuntimeSemanticEvent started = claudeToolStarted(
                        executionId, block, capabilities).stream().findFirst().orElse(null);
                if (started != null) {
                    events.add(started);
                }
            }
        }
        return events;
    }

    private List<RuntimeSemanticEvent> claudeToolStarted(
            String executionId, JsonNode block,
            RuntimeCapabilityMaterialization capabilities) {
        String callId = identifier(block.get("id"));
        String tool = identifier(block.get("name"));
        if (callId == null || tool == null) {
            return Collections.emptyList();
        }
        Map<String, String> names = claudeToolNamesByExecution.computeIfAbsent(
                executionId, ignored -> new ConcurrentHashMap<String, String>());
        boolean firstObservation = names.putIfAbsent(callId, tool) == null;
        String input = safeClaudeInput(tool, block.get("input"), capabilities);
        if (!firstObservation && input == null) {
            return Collections.emptyList();
        }
        if (input != null) {
            if (!markClaudeInputProjected(executionId, callId)) {
                return Collections.emptyList();
            }
        }
        RuntimeSemanticEvent event = input == null
                ? RuntimeSemanticEvent.toolStarted(tool, callId, "RUNNING")
                : RuntimeSemanticEvent.toolStarted(tool, callId, "RUNNING", input);
        return Collections.singletonList(event);
    }

    private List<RuntimeSemanticEvent> claudeToolInputDelta(
            String executionId, JsonNode event,
            RuntimeCapabilityMaterialization capabilities) {
        JsonNode delta = event.path("delta");
        String callId = identifier(delta.get("tool_use_id"));
        Integer blockIndex = integer(event.get("index"));
        if (callId == null && blockIndex != null) {
            Map<Integer, String> calls = claudeToolIdsByBlockExecution.get(executionId);
            callId = calls == null ? null : calls.get(blockIndex);
        }
        if (callId == null) {
            return Collections.emptyList();
        }
        Map<String, String> names = claudeToolNamesByExecution.get(executionId);
        String tool = names == null ? null : names.get(callId);
        String partial = textual(delta.get("partial_json"));
        if (tool == null || partial == null || partial.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, StringBuilder> fragments = claudeToolInputFragmentsByExecution.computeIfAbsent(
                executionId, ignored -> new ConcurrentHashMap<String, StringBuilder>());
        StringBuilder accumulated = fragments.computeIfAbsent(
                callId, ignored -> new StringBuilder());
        if (partial.length() > RuntimeEvent.MAX_SAFE_PAYLOAD_LENGTH
                || accumulated.length()
                > RuntimeEvent.MAX_SAFE_PAYLOAD_LENGTH - partial.length()) {
            fragments.remove(callId);
            return Collections.emptyList();
        }
        accumulated.append(partial);
        JsonNode input = parseObject(accumulated.toString());
        if (input == null || !input.isObject()) {
            return Collections.emptyList();
        }
        fragments.remove(callId);
        String command = safeClaudeInput(tool, input, capabilities);
        if (command == null || !markClaudeInputProjected(executionId, callId)) {
            return Collections.emptyList();
        }
        return Collections.singletonList(RuntimeSemanticEvent.toolStarted(
                tool, callId, "RUNNING", command));
    }

    private boolean markClaudeInputProjected(String executionId, String callId) {
        Set<String> projectedInputs = claudeToolInputsByExecution.computeIfAbsent(
                executionId, ignored -> ConcurrentHashMap.newKeySet());
        return projectedInputs.add(callId);
    }

    private void rememberClaudeToolBlock(String executionId, JsonNode event) {
        Integer index = integer(event.get("index"));
        String callId = identifier(event.path("content_block").get("id"));
        if (index == null || callId == null) {
            return;
        }
        Map<Integer, String> calls = claudeToolIdsByBlockExecution.computeIfAbsent(
                executionId, ignored -> new ConcurrentHashMap<Integer, String>());
        calls.put(index, callId);
    }

    private String claudeToolName(String executionId, String callId) {
        Map<String, String> names = claudeToolNamesByExecution.get(executionId);
        if (names == null) {
            return "claude_tool";
        }
        String tool = names.remove(callId);
        if (names.isEmpty()) {
            claudeToolNamesByExecution.remove(executionId, names);
        }
        Set<String> projectedInputs = claudeToolInputsByExecution.get(executionId);
        if (projectedInputs != null) {
            projectedInputs.remove(callId);
            if (projectedInputs.isEmpty()) {
                claudeToolInputsByExecution.remove(executionId, projectedInputs);
            }
        }
        return tool == null ? "claude_tool" : tool;
    }

    private String safeClaudeInput(
            String tool, JsonNode input,
            RuntimeCapabilityMaterialization capabilities) {
        if (input == null || input.isNull() || input.isEmpty()) {
            return null;
        }
        JsonNode command = input.path("command");
        String value = "Bash".equalsIgnoreCase(tool) && command.isTextual()
                ? command.asText()
                : input.isTextual() ? input.asText() : input.toString();
        return safeClaudeText(value,
                capabilities, RuntimeEvent.MAX_SAFE_PAYLOAD_LENGTH);
    }

    private String safeClaudeResult(
            JsonNode content, RuntimeCapabilityMaterialization capabilities) {
        if (content == null || content.isNull()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode block : content) {
                if (block.isTextual()) {
                    result.append(block.asText());
                } else if (block.path("text").isTextual()) {
                    result.append(block.path("text").asText());
                } else {
                    result.append(block);
                }
            }
        } else if (content.isTextual()) {
            result.append(content.asText());
        } else {
            result.append(content);
        }
        return safeClaudeText(result.toString(), capabilities,
                RuntimeEvent.MAX_SAFE_PAYLOAD_LENGTH);
    }

    private String safeClaudeText(
            String value, RuntimeCapabilityMaterialization capabilities,
            int maxLength) {
        String redacted = capabilities == null
                ? value : capabilities.redact(value, outputRedactor);
        return outputRedactor.boundEvidenceLine(
                outputRedactor.sanitizeDisplayText(redacted), maxLength);
    }

    public String providerEventType(String providerLine) {
        return providerEventType(parseObject(providerLine));
    }

    void clearExecution(String executionId) {
        if (executionId != null) {
            claudeToolNamesByExecution.remove(executionId);
            claudeToolInputsByExecution.remove(executionId);
            claudeToolIdsByBlockExecution.remove(executionId);
            claudeToolInputFragmentsByExecution.remove(executionId);
        }
    }

    private boolean isWorkbenchSpecificEvent(
            JsonNode root, String providerEventType) {
        if (root == null) {
            return false;
        }
        if (!ITEM_STARTED.equals(providerEventType)
                && !ITEM_COMPLETED.equals(providerEventType)) {
            return false;
        }
        JsonNode item = root.get("item");
        if (item == null || !item.isObject()) {
            return false;
        }
        String itemType = item.path("type").asText("");
        return MCP_TOOL_CALL.equals(itemType)
                || FILE_CHANGE.equals(itemType);
    }

    private String providerEventType(JsonNode root) {
        JsonNode type = root == null ? null : root.get("type");
        if (type != null && type.isTextual()
                && SAFE_EVENT_TYPE.matcher(type.asText()).matches()) {
            return type.asText();
        }
        return "";
    }

    private JsonNode parseObject(String providerLine) {
        try {
            JsonNode root = MAPPER.readTree(providerLine);
            return root != null && root.isObject() ? root : null;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private String normalizedAssistantText(
            JsonNode root, String providerEventType,
            RuntimeCapabilityMaterialization capabilities) {
        if (root == null || !ITEM_COMPLETED.equals(providerEventType)) {
            return null;
        }
        JsonNode item = root.get("item");
        JsonNode text = item == null ? null : item.get("text");
        if (item == null || !"agent_message".equals(item.path("type").asText())
                || text == null || !text.isTextual()
                || text.asText().trim().isEmpty()) {
            return null;
        }
        String safe = text.asText();
        if (capabilities != null) {
            safe = capabilities.redact(safe, outputRedactor);
        }
        return outputRedactor.boundEvidenceLine(
                safe, RuntimeEvent.MAX_SAFE_PAYLOAD_LENGTH);
    }

    private SemanticProjection semantics(
            JsonNode root, String providerEventType,
            RuntimeCapabilityMaterialization capabilities,
            WorkspaceLayout workspaceLayout) {
        if (root == null || !ITEM_STARTED.equals(providerEventType)
                && !ITEM_COMPLETED.equals(providerEventType)) {
            return SemanticProjection.empty();
        }
        JsonNode item = root.get("item");
        if (item == null || !item.isObject()) {
            return SemanticProjection.empty();
        }
        String itemType = item.path("type").asText("");
        try {
            if ("command_execution".equals(itemType)) {
                return commandSemantics(
                        providerEventType, item, capabilities, workspaceLayout);
            }
            if (MCP_TOOL_CALL.equals(itemType)) {
                return mcpSemantics(providerEventType, item);
            }
            if (FILE_CHANGE.equals(itemType)) {
                return fileSemantics(
                        providerEventType, item, workspaceLayout);
            }
        } catch (IllegalArgumentException failure) {
            return SemanticProjection.empty();
        }
        return SemanticProjection.empty();
    }

    private SemanticProjection commandSemantics(
            String providerEventType, JsonNode item,
            RuntimeCapabilityMaterialization capabilities,
            WorkspaceLayout workspaceLayout) {
        JsonNode commandNode = item.get("command");
        if (commandNode == null || !commandNode.isTextual()) {
            return SemanticProjection.empty();
        }
        RuntimeCommandAssessment assessment = commandPolicy.assess(
                commandNode.asText());
        if (assessment.isBlocked()) {
            RuntimeSemanticEvent blocked = RuntimeSemanticEvent.operationBlocked(
                    assessment.blockedOperation().get().name(),
                    BLOCK_REASON, BLOCK_SUMMARY);
            return SemanticProjection.blocked(
                    Collections.singletonList(blocked));
        }
        if (workspaceLayout == null) {
            return SemanticProjection.empty();
        }
        RuntimeRepositoryPathResolver resolver =
                new RuntimeRepositoryPathResolver(workspaceLayout);
        String cwd = textual(item.get("cwd"));
        if (cwd == null) {
            cwd = workspaceLayout.getPrimaryRepositoryRoot().toString();
        }
        String repositoryKey = resolver.repositoryKeyForWorkingDirectory(cwd);
        String callId = identifier(item.get("id"));
        if (callId == null) {
            return SemanticProjection.empty();
        }
        List<RuntimeSemanticEvent> events =
                new ArrayList<RuntimeSemanticEvent>();
        RuntimeCommandClass commandClass = assessment.getCommandClass();
        if (ITEM_STARTED.equals(providerEventType)) {
            events.add(RuntimeSemanticEvent.toolStarted(
                    "shell", callId, "RUNNING",
                    safeCommandContent(commandNode.asText(), capabilities)));
            events.add(RuntimeSemanticEvent.commandStarted(
                    repositoryKey, commandClass.name()));
            addProgress(events, repositoryKey, commandClass,
                    "RUNNING", "命令已启动");
        } else if (ITEM_COMPLETED.equals(providerEventType)) {
            Integer exitCode = integer(item.get("exit_code"));
            boolean succeeded = exitCode != null && exitCode.intValue() == 0
                    && !"failed".equals(item.path("status").asText());
            events.add(RuntimeSemanticEvent.toolFinished(
                    "shell", callId,
                    succeeded ? "SUCCEEDED" : "FAILED",
                    safeCommandOutput(item, capabilities)));
            events.add(RuntimeSemanticEvent.commandFinished(
                    repositoryKey, commandClass.name(), exitCode,
                    succeeded ? "SUCCEEDED" : "FAILED"));
            addProgress(events, repositoryKey, commandClass,
                    succeeded ? "PASSED" : "FAILED",
                    succeeded ? "命令已完成" : "命令执行失败");
        }
        return SemanticProjection.of(events);
    }

    private String safeCommandContent(
            String command,
            RuntimeCapabilityMaterialization capabilities) {
        String redacted = capabilities == null
                ? command : capabilities.redact(command, outputRedactor);
        return outputRedactor.boundEvidenceLine(
                outputRedactor.sanitizeDisplayText(redacted),
                RuntimeEvent.MAX_SAFE_PAYLOAD_LENGTH);
    }

    private String safeCommandOutput(
            JsonNode item,
            RuntimeCapabilityMaterialization capabilities) {
        String output = textual(item.get("aggregated_output"));
        if (output == null) {
            output = "";
        }
        String redacted = capabilities == null
                ? output : capabilities.redact(output, outputRedactor);
        return outputRedactor.sanitizeDisplayText(redacted);
    }

    private void addProgress(
            List<RuntimeSemanticEvent> events, String repositoryKey,
            RuntimeCommandClass commandClass, String status,
            String summary) {
        if (commandClass == RuntimeCommandClass.TEST) {
            events.add(RuntimeSemanticEvent.testProgress(
                    repositoryKey, "runtime-test-command", status, summary));
        } else if (commandClass == RuntimeCommandClass.BUILD) {
            events.add(RuntimeSemanticEvent.testProgress(
                    repositoryKey, "runtime-build-command", status, summary));
        }
    }

    private SemanticProjection mcpSemantics(
            String providerEventType, JsonNode item) {
        String callId = identifier(item.get("id"));
        String server = identifier(item.get("server"));
        String tool = identifier(item.get("tool"));
        if (callId == null || server == null || tool == null) {
            return SemanticProjection.empty();
        }
        String displayTool = server + "/" + tool;
        if (ITEM_STARTED.equals(providerEventType)) {
            return SemanticProjection.of(Collections.singletonList(
                    RuntimeSemanticEvent.toolStarted(
                            displayTool, callId, "RUNNING")));
        }
        String providerStatus = item.path("status").asText("");
        String status = "completed".equals(providerStatus)
                ? "SUCCEEDED" : "FAILED";
        return SemanticProjection.of(Collections.singletonList(
                RuntimeSemanticEvent.toolFinished(
                        displayTool, callId, status)));
    }

    private SemanticProjection fileSemantics(
            String providerEventType, JsonNode item,
            WorkspaceLayout workspaceLayout) {
        if (!ITEM_COMPLETED.equals(providerEventType)
                || workspaceLayout == null
                || !"completed".equals(item.path("status").asText())) {
            return SemanticProjection.empty();
        }
        RuntimeRepositoryPathResolver resolver =
                new RuntimeRepositoryPathResolver(workspaceLayout);
        String itemId = identifier(item.get("id"));
        JsonNode changes = item.get("changes");
        if (itemId == null || changes == null || !changes.isArray()) {
            return SemanticProjection.empty();
        }
        List<RuntimeSemanticEvent> events =
                new ArrayList<RuntimeSemanticEvent>();
        for (JsonNode change : changes) {
            String path = textual(change.get("path"));
            String changeType = changeType(textual(change.get("kind")));
            if (path == null || changeType == null) {
                continue;
            }
            try {
                RuntimeRepositoryPathResolver.ResolvedFile resolved =
                        resolver.resolveFile(path);
                String contentVersion = "sha256:" + CanonicalHashing.sha256(
                        itemId + "\n" + resolved.getRepositoryKey()
                                + "\n" + resolved.getRelativePath()
                                + "\n" + changeType);
                events.add(RuntimeSemanticEvent.fileChanged(
                        resolved.getRepositoryKey(), resolved.getRelativePath(),
                        changeType, contentVersion));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return SemanticProjection.of(events);
    }

    private String changeType(String providerKind) {
        if (providerKind == null) {
            return null;
        }
        String normalized = providerKind.toLowerCase(Locale.ROOT);
        if ("add".equals(normalized)) {
            return "ADDED";
        }
        if ("delete".equals(normalized)) {
            return "DELETED";
        }
        if ("update".equals(normalized)) {
            return "MODIFIED";
        }
        return null;
    }

    private String identifier(JsonNode node) {
        String value = textual(node);
        if (value == null || value.trim().isEmpty()
                || value.length() > 256
                || containsControlCharacter(value)) {
            return null;
        }
        return value.trim();
    }

    private String textual(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private Integer integer(JsonNode node) {
        return node != null && node.canConvertToInt()
                ? Integer.valueOf(node.intValue()) : null;
    }

    private boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解码结果同时保留进程内核判定技术失败和安全阻断所需的事实。
     */
    @Getter
    public static final class DecodedEvent {

        private final RuntimeEvent event;
        private final String providerEventType;
        private final boolean turnFailed;
        private final boolean operationBlocked;

        private DecodedEvent(RuntimeEvent event, String providerEventType,
                             boolean turnFailed, boolean operationBlocked) {
            this.event = event;
            this.providerEventType = providerEventType;
            this.turnFailed = turnFailed;
            this.operationBlocked = operationBlocked;
        }

        /**
         * 未识别事件：不产生 RuntimeEvent，不发送到 sink。
         */
        public static DecodedEvent skipped() {
            return new DecodedEvent(null, "", false, false);
        }
    }

    @Getter
    private static final class SemanticProjection {

        private final List<RuntimeSemanticEvent> events;
        private final boolean operationBlocked;

        private SemanticProjection(
                List<RuntimeSemanticEvent> events,
                boolean operationBlocked) {
            this.events = Collections.unmodifiableList(
                    new ArrayList<RuntimeSemanticEvent>(events));
            this.operationBlocked = operationBlocked;
        }

        private static SemanticProjection empty() {
            return new SemanticProjection(
                    Collections.<RuntimeSemanticEvent>emptyList(), false);
        }

        private static SemanticProjection of(
                List<RuntimeSemanticEvent> events) {
            return new SemanticProjection(events, false);
        }

        private static SemanticProjection blocked(
                List<RuntimeSemanticEvent> events) {
            return new SemanticProjection(events, true);
        }
    }
}
