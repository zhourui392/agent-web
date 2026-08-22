package com.example.agentweb.infra.cli;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.config.cli.AgentCliProperties;
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
    private static final String EFFORT_FLAG = "--effort";
    /** 无模式/无覆盖时的 CLI 缺省（自 application.yml 模板移入代码，避免与 per-run 覆盖重复下发）。 */
    private static final String DEFAULT_PERMISSION_MODE = "acceptEdits";
    private static final String PERMISSION_MODE_FLAG = "--permission-mode";
    private static final String APPEND_SYSTEM_PROMPT_FLAG = "--append-system-prompt";
    private static final String MCP_CONFIG_FLAG = "--mcp-config";
    private static final String STRICT_MCP_CONFIG_FLAG = "--strict-mcp-config";
    private static final String PLUGIN_DIR_FLAG = "--plugin-dir";
    private static final String SESSION_ID_FIELD = "session_id";
    private static final String TYPE_FIELD = "type";
    private static final String RESULT_EVENT_TYPE = "result";
    private static final String JSON_QUOTE = "\"";

    @Override
    public AgentType type() {
        return AgentType.CLAUDE;
    }

    @Override
    public String credentialEnvironmentVariable() {
        return "ANTHROPIC_API_KEY";
    }

    @Override
    public String endpointEnvironmentVariable() {
        return "ANTHROPIC_BASE_URL";
    }

    @Override
    public List<String> buildCommand(BuildContext ctx) {
        AgentCliProperties.Client cfg = ctx.getConfig();
        // 1. 参数验证
        validateExec(cfg);

        // 2. 渲染基础命令（若模板自带 --permission-mode 且本次有 per-run 覆盖，剥除避免重复 flag）
        List<String> cmd = renderTemplate(cfg, ctx.getUserMessage());
        String permissionMode = ctx.getPermissionMode() == null
                || ctx.getPermissionMode().trim().isEmpty()
                ? DEFAULT_PERMISSION_MODE : ctx.getPermissionMode().trim();
        stripExistingPermissionMode(cmd);

        // 3. 按需追加 resume flag
        appendResumeFlagIfPresent(cmd, ctx.getResumeId());

        // 4. 按需追加 --model (仅当本次调用显式指定, 如 refinery 评分走廉价模型); 空则用 CLI 默认
        appendModelFlagIfPresent(cmd, ctx.getModel());
        appendEffortFlagIfPresent(cmd, ctx.getReasoningEffort());

        // 5. 模式能力下发（全部来源于会话模式快照，无模式 = 零行为变化）
        cmd.add(PERMISSION_MODE_FLAG);
        cmd.add(permissionMode);
        appendValueFlagIfPresent(cmd, APPEND_SYSTEM_PROMPT_FLAG, ctx.getAppendSystemPrompt());
        if (ctx.getMcpConfigPath() != null && !ctx.getMcpConfigPath().trim().isEmpty()) {
            // 隔离用户本机已配 MCP server，能力清单只来自模式快照
            cmd.add(STRICT_MCP_CONFIG_FLAG);
            cmd.add(MCP_CONFIG_FLAG);
            cmd.add(ctx.getMcpConfigPath().trim());
        }
        appendValueFlagIfPresent(cmd, PLUGIN_DIR_FLAG, ctx.getCapabilityDir());
        log.debug("claude-command-built resumeId={} permissionMode={} mcpConfig={} pluginDir={} argCount={}",
                ctx.getResumeId(), permissionMode, ctx.getMcpConfigPath() != null,
                ctx.getCapabilityDir() != null, cmd.size());
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

    private void appendEffortFlagIfPresent(List<String> cmd, String effort) {
        if (effort == null || effort.trim().isEmpty()) {
            return;
        }
        cmd.add(EFFORT_FLAG);
        cmd.add(effort.trim());
    }

    private void appendValueFlagIfPresent(List<String> cmd, String flag, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        cmd.add(flag);
        cmd.add(value.trim());
    }

    /** 剥除模板参数中已有的 --permission-mode 对，保证 per-run 覆盖不产生重复 flag。 */
    private void stripExistingPermissionMode(List<String> cmd) {
        java.util.Iterator<String> iterator = cmd.iterator();
        while (iterator.hasNext()) {
            if (PERMISSION_MODE_FLAG.equals(iterator.next())) {
                iterator.remove();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
                return;
            }
        }
    }
}
