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
 * <p>工厂只接受前端合同需要的最小字段；不提供原始命令、环境、stderr 或绝对路径字段。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuntimeSemanticEvent {

    private static final int MAX_IDENTIFIER_LENGTH = 512;
    private static final int MAX_TEXT_LENGTH = 65_536;

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
        return tool("tool_started", tool, callId, status);
    }

    public static RuntimeSemanticEvent toolFinished(
            String tool, String callId, String status) {
        return tool("tool_finished", tool, callId, status);
    }

    private static RuntimeSemanticEvent tool(
            String eventType, String tool, String callId, String status) {
        Map<String, Object> data = data();
        data.put("tool", requireIdentifier(
                tool, "tool name", MAX_IDENTIFIER_LENGTH));
        data.put("callId", requireIdentifier(
                callId, "tool call id", MAX_IDENTIFIER_LENGTH));
        data.put("status", requireIdentifier(status, "tool status", 80));
        return new RuntimeSemanticEvent(eventType, data);
    }

    public static RuntimeSemanticEvent commandStarted(
            String repositoryKey, String commandClass) {
        Map<String, Object> data = commandData(repositoryKey, commandClass);
        return new RuntimeSemanticEvent("command_started", data);
    }

    public static RuntimeSemanticEvent commandFinished(
            String repositoryKey, String commandClass, Integer exitCode) {
        Map<String, Object> data = commandData(repositoryKey, commandClass);
        if (exitCode != null) {
            data.put("exitCode", exitCode);
        }
        return new RuntimeSemanticEvent("command_finished", data);
    }

    private static Map<String, Object> commandData(
            String repositoryKey, String commandClass) {
        Map<String, Object> data = data();
        data.put("repositoryKey", requireIdentifier(
                repositoryKey, "repository key", MAX_IDENTIFIER_LENGTH));
        data.put("commandClass", requireIdentifier(
                commandClass, "command class", 128));
        return data;
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
