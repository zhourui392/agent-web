package com.example.agentweb.infra.cli;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.AgentCliProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Claude Code CLI 方言：
 * <ul>
 *   <li>命令模板从 {@link AgentCliProperties.Client#getArgs()} 读取，支持 {@code ${MESSAGE}} 占位符替换。</li>
 *   <li>{@code resume} 通过同位 flag {@code --resume <id>} 实现，附加在命令末尾。</li>
 *   <li>{@code session_id} 来自 stream-json 首个 {@code system.init} 事件。</li>
 * </ul>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
@Component
@Slf4j
public class ClaudeCliDialect implements CliDialect {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String MESSAGE_PLACEHOLDER = "${MESSAGE}";
    private static final String RESUME_FLAG = "--resume";
    private static final String MODEL_FLAG = "--model";
    private static final String SESSION_ID_FIELD = "session_id";
    private static final String TYPE_FIELD = "type";
    private static final String RESULT_EVENT_TYPE = "result";
    private static final String JSON_QUOTE = "\"";

    @Override
    public AgentType type() {
        return AgentType.CLAUDE;
    }

    @Override
    public List<String> buildCommand(BuildContext ctx) {
        AgentCliProperties.Client cfg = ctx.getConfig();
        // 1. 参数验证
        validateExec(cfg);

        // 2. 渲染基础命令
        List<String> cmd = renderTemplate(cfg, ctx.getUserMessage());

        // 3. 按需追加 resume flag
        appendResumeFlagIfPresent(cmd, ctx.getResumeId());

        // 4. 按需追加 --model (仅当本次调用显式指定, 如 refinery 评分走廉价模型); 空则用 CLI 默认
        appendModelFlagIfPresent(cmd, ctx.getModel());
        log.debug("claude-command-built resumeId={} argCount={}", ctx.getResumeId(), cmd.size());
        return cmd;
    }

    @Override
    public String extractResumeId(String stdoutLine) {
        if (stdoutLine == null || !stdoutLine.contains(JSON_QUOTE + SESSION_ID_FIELD + JSON_QUOTE)) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(stdoutLine);
            JsonNode sidNode = node.get(SESSION_ID_FIELD);
            if (sidNode == null) {
                return null;
            }
            String value = sidNode.asText();
            return (value == null || value.isEmpty()) ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public List<String> normalizeChunk(String stdoutLine) {
        return Collections.singletonList(stdoutLine);
    }

    @Override
    public boolean isTurnEnd(String stdoutLine) {
        if (stdoutLine == null || !stdoutLine.contains(JSON_QUOTE + RESULT_EVENT_TYPE + JSON_QUOTE)) {
            return false;
        }
        try {
            JsonNode typeNode = OBJECT_MAPPER.readTree(stdoutLine).get(TYPE_FIELD);
            return typeNode != null && RESULT_EVENT_TYPE.equals(typeNode.asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void validateExec(AgentCliProperties.Client cfg) {
        if (cfg == null || cfg.getExec() == null || cfg.getExec().trim().isEmpty()) {
            throw new IllegalStateException("Executable not configured");
        }
    }

    private List<String> renderTemplate(AgentCliProperties.Client cfg, String userMessage) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(cfg.getExec());
        String safeMessage = userMessage == null ? "" : userMessage;
        for (String arg : cfg.getArgs()) {
            if (arg.contains(MESSAGE_PLACEHOLDER)) {
                cmd.add(arg.replace(MESSAGE_PLACEHOLDER, safeMessage));
            } else {
                cmd.add(arg);
            }
        }
        return cmd;
    }

    private void appendResumeFlagIfPresent(List<String> cmd, String resumeId) {
        if (resumeId == null || resumeId.trim().isEmpty()) {
            return;
        }
        cmd.add(RESUME_FLAG);
        cmd.add(resumeId.trim());
    }

    private void appendModelFlagIfPresent(List<String> cmd, String model) {
        if (model == null || model.trim().isEmpty()) {
            return;
        }
        cmd.add(MODEL_FLAG);
        cmd.add(model.trim());
    }
}
