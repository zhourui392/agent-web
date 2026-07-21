package com.example.agentweb.infra.cli;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.AgentCliProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Codex CLI 方言。
 * <p>命令拼装双路径：
 * <ul>
 *   <li><b>真实 codex exec 路径</b> (cfg.args 为空)：按
 *       {@code codex exec [resume <id>] --json [--skip-git-repo-check] [--dangerously-bypass-approvals-and-sandbox]
 *       [--cd <dir>] [--model <id>] [extra-args...] -} 拼装。</li>
 *   <li><b>Legacy 模板路径</b> (cfg.args 非空)：沿用 {@code ${MESSAGE}} 占位符模板渲染，
 *       为 stub 测试与历史配置保留兼容。</li>
 * </ul>
 * <p>{@code resumeId} 来自 codex 的 {@code thread.started.thread_id} 事件。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
@Component
@Slf4j
public class CodexCliDialect implements CliDialect {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final CodexEventNormalizer NORMALIZER = new CodexEventNormalizer();
    private static final String MESSAGE_PLACEHOLDER = "${MESSAGE}";

    private static final String EXEC_SUBCOMMAND = "exec";
    private static final String RESUME_SUBCOMMAND = "resume";
    private static final String JSON_FLAG = "--json";
    private static final String SKIP_GIT_CHECK_FLAG = "--skip-git-repo-check";
    private static final String SANDBOX_BYPASS_FLAG = "--dangerously-bypass-approvals-and-sandbox";
    private static final String CD_FLAG = "--cd";
    private static final String MODEL_FLAG = "--model";
    private static final String STDIN_SENTINEL = "-";

    private static final String THREAD_STARTED_EVENT = "thread.started";
    private static final String TURN_COMPLETED_EVENT = "turn.completed";
    private static final String TURN_FAILED_EVENT = "turn.failed";
    private static final String EVENT_TYPE_FIELD = "type";
    private static final String THREAD_ID_FIELD = "thread_id";

    @Override
    public AgentType type() {
        return AgentType.CODEX;
    }

    @Override
    public List<String> buildCommand(BuildContext ctx) {
        AgentCliProperties.Client cfg = ctx.getConfig();
        validateExec(cfg);

        // 1. 兼容路径：用户配置了 args 模板（典型场景：stub 测试 / 历史 yml）→ 走模板渲染
        if (hasTemplateArgs(cfg)) {
            List<String> tplCmd = renderTemplate(cfg, ctx.getUserMessage());
            log.debug("codex-command-built path=template resumeId={} argCount={}",
                    ctx.getResumeId(), tplCmd.size());
            return tplCmd;
        }

        // 2. 真实路径：按 codex exec 子命令拼装
        List<String> cmd = renderCodexExec(cfg, ctx);
        log.debug("codex-command-built path=exec resumeId={} resuming={} argCount={}",
                ctx.getResumeId(), isResuming(ctx.getResumeId()), cmd.size());
        return cmd;
    }

    @Override
    public String extractResumeId(String stdoutLine) {
        if (stdoutLine == null || !stdoutLine.contains(THREAD_STARTED_EVENT)) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(stdoutLine);
            if (!isThreadStartedEvent(node)) {
                return null;
            }
            return readNonEmptyString(node, THREAD_ID_FIELD);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public List<String> normalizeChunk(String stdoutLine) {
        return NORMALIZER.normalize(stdoutLine);
    }

    @Override
    public boolean isTurnEnd(String stdoutLine) {
        if (!mentionsTurnEndEvent(stdoutLine)) {
            return false;
        }
        try {
            JsonNode typeNode = OBJECT_MAPPER.readTree(stdoutLine).get(EVENT_TYPE_FIELD);
            if (typeNode == null) {
                return false;
            }
            String type = typeNode.asText();
            return TURN_COMPLETED_EVENT.equals(type) || TURN_FAILED_EVENT.equals(type);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 快速预筛：行文本是否提及回合终结事件名，避免对无关行做 JSON 解析。 */
    private boolean mentionsTurnEndEvent(String stdoutLine) {
        if (stdoutLine == null) {
            return false;
        }
        return stdoutLine.contains(TURN_COMPLETED_EVENT) || stdoutLine.contains(TURN_FAILED_EVENT);
    }

    private void validateExec(AgentCliProperties.Client cfg) {
        if (cfg == null || cfg.getExec() == null || cfg.getExec().trim().isEmpty()) {
            throw new IllegalStateException("Executable not configured");
        }
    }

    private boolean hasTemplateArgs(AgentCliProperties.Client cfg) {
        return cfg.getArgs() != null && !cfg.getArgs().isEmpty();
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

    private List<String> renderCodexExec(AgentCliProperties.Client cfg, BuildContext ctx) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(cfg.getExec());
        cmd.add(EXEC_SUBCOMMAND);

        boolean resuming = isResuming(ctx.getResumeId());
        appendResumeSubcommandIfPresent(cmd, ctx.getResumeId());
        cmd.add(JSON_FLAG);
        appendFlagIfTrue(cmd, cfg.isSkipGitCheck(), SKIP_GIT_CHECK_FLAG);
        appendFlagIfTrue(cmd, cfg.isSandboxBypass(), SANDBOX_BYPASS_FLAG);
        // codex exec resume 不接受 --cd (强制继承原会话 cwd), 拼上会令进程 exit 2; 仅新会话拼 --cd
        if (!resuming) {
            appendKeyValueIfPresent(cmd, CD_FLAG, ctx.getWorkingDir());
        }
        // 本次调用显式指定的 model (如 refinery 评分) 优先于全局 cfg.model
        appendKeyValueIfPresent(cmd, MODEL_FLAG, resolveModel(cfg, ctx));
        appendExtraArgs(cmd, cfg.getExtraArgs());

        cmd.add(STDIN_SENTINEL);
        return cmd;
    }

    private boolean isResuming(String resumeId) {
        return resumeId != null && !resumeId.trim().isEmpty();
    }

    private String resolveModel(AgentCliProperties.Client cfg, BuildContext ctx) {
        String override = ctx.getModel();
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        return cfg.getModel();
    }

    private void appendResumeSubcommandIfPresent(List<String> cmd, String resumeId) {
        if (!isResuming(resumeId)) {
            return;
        }
        cmd.add(RESUME_SUBCOMMAND);
        cmd.add(resumeId.trim());
    }

    private void appendFlagIfTrue(List<String> cmd, boolean enabled, String flag) {
        if (enabled) {
            cmd.add(flag);
        }
    }

    private void appendKeyValueIfPresent(List<String> cmd, String flag, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        cmd.add(flag);
        cmd.add(value);
    }

    private void appendExtraArgs(List<String> cmd, List<String> extraArgs) {
        if (extraArgs == null || extraArgs.isEmpty()) {
            return;
        }
        cmd.addAll(extraArgs);
    }

    private boolean isThreadStartedEvent(JsonNode node) {
        JsonNode typeNode = node.get(EVENT_TYPE_FIELD);
        return typeNode != null && THREAD_STARTED_EVENT.equals(typeNode.asText());
    }

    private String readNonEmptyString(JsonNode node, String field) {
        JsonNode valueNode = node.get(field);
        if (valueNode == null) {
            return null;
        }
        String value = valueNode.asText();
        return (value == null || value.isEmpty()) ? null : value;
    }
}
