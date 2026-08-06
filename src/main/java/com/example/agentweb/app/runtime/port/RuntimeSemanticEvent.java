package com.example.agentweb.app.runtime.port;

import lombok.Getter;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider 输出中可恢复、可展示的结构化语义投影。
 *
 * <p>工厂只接受前端合同需要的最小字段；命令工具可携带已脱敏、有界的命令和输出，
 * 不提供环境字段。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuntimeSemanticEvent {

    public static final long MAX_TOOL_DURATION_MILLIS = 86_400_000L;

    private static final int MAX_IDENTIFIER_LENGTH = 512;
    private static final int MAX_TEXT_LENGTH = 65_536;
    private static final int MAX_COMMAND_SUMMARY_LENGTH = 1024;
    private static final int MAX_COMMAND_OUTPUT_LENGTH = 2000;

    private final String eventType;
    private final Map<String, Object> data;

    private RuntimeSemanticEvent(String eventType, Map<String, Object> data) {
        this.eventType = requireIdentifier(eventType, "semantic event type", 80);
        if (data == null) {
            throw new IllegalArgumentException(
                    "semantic event data must not be null");
        }
        this.data = Collections.unmodifiableMap(
                new LinkedHashMap<String, Object>(data));
    }

    public static RuntimeSemanticEvent agentChunk(String content) {
        Map<String, Object> data = data();
        data.put("content", requireText(content, "agent content", MAX_TEXT_LENGTH));
        return new RuntimeSemanticEvent("agent_chunk", data);
    }

    public static RuntimeSemanticEvent toolStarted(
            String tool, String callId, String status) {
        return tool("tool_started", tool, callId, status, null, null);
    }

    public static RuntimeSemanticEvent toolStarted(
            String tool, String callId, String status,
            String commandContent) {
        return tool("tool_started", tool, callId, status,
                commandContent, null);
    }

    public static RuntimeSemanticEvent toolFinished(
            String tool, String callId, String status) {
        return tool("tool_finished", tool, callId, status, null, null);
    }

    public static RuntimeSemanticEvent toolFinished(
            String tool, String callId, String status,
            String outputContent) {
        return tool("tool_finished", tool, callId, status,
                null, outputContent);
    }

    public RuntimeSemanticEvent withDurationMs(long durationMs) {
        if (!"tool_finished".equals(eventType)) {
            throw new IllegalStateException(
                    "duration is only valid for finished tool events");
        }
        if (durationMs < 0L || durationMs > MAX_TOOL_DURATION_MILLIS) {
            throw new IllegalArgumentException(
                    "tool duration must be non-negative and bounded");
        }
        Map<String, Object> enriched =
                new LinkedHashMap<String, Object>(data);
        enriched.put("durationMs", Long.valueOf(durationMs));
        return new RuntimeSemanticEvent(eventType, enriched);
    }

    private static RuntimeSemanticEvent tool(
            String eventType, String tool, String callId, String status,
            String commandContent, String outputContent) {
        Map<String, Object> data = data();
        data.put("tool", requireIdentifier(
                tool, "tool name", MAX_IDENTIFIER_LENGTH));
        data.put("callId", requireIdentifier(
                callId, "tool call id", MAX_IDENTIFIER_LENGTH));
        data.put("status", requireIdentifier(status, "tool status", 80));
        if (commandContent != null) {
            data.put("commandContent", requireText(
                    commandContent, "command content", MAX_TEXT_LENGTH));
        }
        if (outputContent != null) {
            boolean truncated = outputContent.length()
                    > MAX_COMMAND_OUTPUT_LENGTH;
            String bounded = truncated
                    ? outputContent.substring(0, MAX_COMMAND_OUTPUT_LENGTH)
                    + "\n... (共 " + outputContent.length() + " 字符，已截断)"
                    : outputContent;
            data.put("outputContent", requireText(
                    bounded, "command output content",
                    MAX_COMMAND_OUTPUT_LENGTH + 80));
            data.put("outputTruncated", Boolean.valueOf(truncated));
        }
        return new RuntimeSemanticEvent(eventType, data);
    }

    public static RuntimeSemanticEvent commandStarted(
            String repositoryKey, String commandClass) {
        Map<String, Object> data = commandData(repositoryKey, commandClass);
        data.put("status", "RUNNING");
        return new RuntimeSemanticEvent("command_started", data);
    }

    public static RuntimeSemanticEvent commandFinished(
            String repositoryKey, String commandClass, Integer exitCode) {
        String status = exitCode != null && exitCode.intValue() == 0
                ? "SUCCEEDED" : "FAILED";
        return commandFinished(
                repositoryKey, commandClass, exitCode, status);
    }

    public static RuntimeSemanticEvent commandFinished(
            String repositoryKey, String commandClass,
            Integer exitCode, String status) {
        Map<String, Object> data = commandData(repositoryKey, commandClass);
        String normalizedStatus = requireCommandCompletionStatus(status);
        data.put("status", normalizedStatus);
        if (exitCode != null) {
            data.put("exitCode", exitCode);
        }
        String result = "SUCCEEDED".equals(normalizedStatus)
                ? "执行成功" : "执行失败";
        String exit = exitCode == null
                ? "退出码未知" : "退出码 " + exitCode;
        data.put("outputSummary", requireSingleLineText(
                data.get("commandClass") + " 类命令" + result
                        + "（" + exit + "）",
                "command output summary", MAX_COMMAND_SUMMARY_LENGTH));
        return new RuntimeSemanticEvent("command_finished", data);
    }

    private static Map<String, Object> commandData(
            String repositoryKey, String commandClass) {
        Map<String, Object> data = data();
        String normalizedRepositoryKey = requireIdentifier(
                repositoryKey, "repository key", MAX_IDENTIFIER_LENGTH);
        String normalizedCommandClass = requireIdentifier(
                commandClass, "command class", 128);
        data.put("repositoryKey", normalizedRepositoryKey);
        data.put("commandClass", normalizedCommandClass);
        data.put("commandSummary", requireSingleLineText(
                "在仓库 " + normalizedRepositoryKey + " 执行 "
                        + normalizedCommandClass + " 类命令",
                "command summary", MAX_COMMAND_SUMMARY_LENGTH));
        return data;
    }

    private static String requireCommandCompletionStatus(String status) {
        String normalized = requireIdentifier(
                status, "command completion status", 80);
        if (!"SUCCEEDED".equals(normalized)
                && !"FAILED".equals(normalized)) {
            throw new IllegalArgumentException(
                    "command completion status is invalid");
        }
        return normalized;
    }

    public static RuntimeSemanticEvent fileChanged(
            String repositoryKey, String relativePath,
            String changeType, String contentVersion) {
        Map<String, Object> data = data();
        data.put("repositoryKey", requireIdentifier(
                repositoryKey, "repository key", MAX_IDENTIFIER_LENGTH));
        data.put("path", requireRelativePath(relativePath));
        data.put("changeType", requireIdentifier(
                changeType, "file change type", 80));
        data.put("contentVersion", requireIdentifier(
                contentVersion, "file content version", 256));
        return new RuntimeSemanticEvent("file_changed", data);
    }

    public static RuntimeSemanticEvent testProgress(
            String repositoryKey, String suite,
            String status, String summary) {
        Map<String, Object> data = data();
        data.put("repositoryKey", requireIdentifier(
                repositoryKey, "repository key", MAX_IDENTIFIER_LENGTH));
        data.put("suite", requireIdentifier(suite, "test suite", 512));
        data.put("status", requireIdentifier(status, "test status", 80));
        data.put("summary", requireText(summary, "test summary", 4000));
        return new RuntimeSemanticEvent("test_progress", data);
    }

    public static RuntimeSemanticEvent operationBlocked(
            String operationType, String reasonCode, String summary) {
        Map<String, Object> data = data();
        data.put("operationType", requireIdentifier(
                operationType, "operation type", 80));
        data.put("reasonCode", requireIdentifier(
                reasonCode, "operation block reason", 160));
        data.put("summary", requireText(
                summary, "operation block summary", 4000));
        return new RuntimeSemanticEvent("operation_blocked", data);
    }

    private static Map<String, Object> data() {
        return new LinkedHashMap<String, Object>();
    }

    private static String requireIdentifier(
            String value, String name, int maximumLength) {
        String required = requireText(value, name, maximumLength).trim();
        if (required.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return required;
    }

    private static String requireText(
            String value, String name, int maximumLength) {
        if (value == null || value.length() > maximumLength
                || containsDisallowedControlCharacter(value)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static String requireSingleLineText(
            String value, String name, int maximumLength) {
        String required = requireText(value, name, maximumLength);
        for (int index = 0; index < required.length(); index++) {
            if (Character.isISOControl(required.charAt(index))) {
                throw new IllegalArgumentException(name + " is invalid");
            }
        }
        return required;
    }

    private static String requireRelativePath(String value) {
        String normalized = requireText(
                value, "relative file path", 2048).replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/")
                || normalized.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(
                    "file path must be repository-relative");
        }
        try {
            Path path = Paths.get(normalized).normalize();
            if (path.isAbsolute() || path.getNameCount() == 0
                    || "..".equals(path.getName(0).toString())
                    || !normalized.equals(path.toString().replace('\\', '/'))) {
                throw new IllegalArgumentException(
                        "file path must be normalized and repository-relative");
            }
            return normalized;
        } catch (InvalidPathException failure) {
            throw new IllegalArgumentException("relative file path is invalid", failure);
        }
    }

    private static boolean containsDisallowedControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\0'
                    || Character.isISOControl(character)
                    && character != '\n' && character != '\r'
                    && character != '\t') {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "RuntimeSemanticEvent{eventType='" + eventType
                + "', dataKeys=" + data.keySet() + '}';
    }
}
