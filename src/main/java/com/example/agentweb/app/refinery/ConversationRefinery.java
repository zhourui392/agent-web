package com.example.agentweb.app.refinery;

import com.example.agentweb.app.StreamOutputExtractor;
import com.example.agentweb.app.agentrun.LlmJsonExtractor;
import com.example.agentweb.config.PromptTemplateLoader;
import com.example.agentweb.config.refinery.RefineryProperties;
import com.example.agentweb.domain.refinery.ConversationTurn;
import com.example.agentweb.domain.refinery.ConversationView;
import com.example.agentweb.domain.refinery.RefinedContent;
import com.example.agentweb.domain.refinery.TtlCategory;
import com.example.agentweb.app.agentrun.port.AgentCliInvoker;
import com.example.agentweb.app.agentrun.port.CliInvokeException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 把 {@link ConversationView} 喂给 agent CLI, 让 LLM 评分 + 提炼可复用结论 (JSON 输出).
 *
 * <p>本类对源类型无感, 只看 {@link ConversationTurn} 列表 + agentType +
 * workingDir. 由 {@link ChatViewBuilder}
 * 在上游把聚合根转成 view 投递进来.</p>
 *
 * <p>失败语义: CLI 失败 / JSON 解析失败 / 字段校验失败统一抛 {@link RefineException},
 * 由 AppService 在 catch 后写 last_error, 不重试.</p>
 *
 * <p>切片策略: 按 {@code refine.token-budget} 粗估 4 字符 / token, 从最新回合倒序保留,
 * 老回合丢弃 (保留排障的"结论上下文", 丢掉前期试探).</p>
 *
 * <p>脱敏: 拼 prompt 前对每条 turn 应用 {@code privacy.redact-patterns}, 替换为 {@code [REDACTED]},
 * 防 API key / JWT / 用户名路径段流入向量.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-02
 */
@Component
@Slf4j
public class ConversationRefinery {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_PROMPT_RESOURCE = "/refinery-refine-prompt.md";
    private static final String REDACTED = "[REDACTED]";
    private static final String TYPE_ASSISTANT = "assistant";
    private static final int CHARS_PER_TOKEN = 4;

    /** 瞬态服务端错误识别: 上游 5xx / 429 / 明确的"暂时不可用"短语, 仅这些值得重试. */
    private static final Pattern TRANSIENT_ERROR_PATTERN = Pattern.compile(
            "(?i)\\b(50[0-4]|429)\\b"
                    + "|service unavailable|temporarily unavailable|overloaded"
                    + "|too many requests|bad gateway|gateway time");

    private final AgentCliInvoker cliInvoker;
    private final RefineryProperties props;
    private final StreamOutputExtractor outputExtractor;
    private final String promptTemplate;
    private final Sleeper sleeper;

    @Autowired
    public ConversationRefinery(AgentCliInvoker cliInvoker, RefineryProperties props,
                                StreamOutputExtractor outputExtractor) {
        this(cliInvoker, props, outputExtractor, defaultSleeper());
    }

    /** 测试可注入 no-op {@link Sleeper}, 避免退避真实等待. */
    ConversationRefinery(AgentCliInvoker cliInvoker, RefineryProperties props,
                         StreamOutputExtractor outputExtractor, Sleeper sleeper) {
        this.cliInvoker = cliInvoker;
        this.props = props;
        this.outputExtractor = outputExtractor;
        this.sleeper = sleeper;
        this.promptTemplate = PromptTemplateLoader.load(
                props.getRefine().getPromptTemplate(),
                DEFAULT_PROMPT_RESOURCE,
                inlineFallback());
    }

    private static Sleeper defaultSleeper() {
        return Thread::sleep;
    }

    /** 退避 sleep 抽象. 生产用 {@link Thread#sleep(long)}, 单测注入记录式 no-op. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public RefineResult refine(ConversationView view) {
        String messages = sliceAndSanitize(view.getTurns());
        String prompt = promptTemplate.replace("{{messages}}", messages);
        String raw = invokeCli(view, prompt);
        String jsonText = extractJson(raw);
        return parseResult(jsonText);
    }

    /**
     * 调 CLI 评分, 对瞬态服务端错误 (中转 5xx / 429) 做指数退避重试.
     *
     * <p>只重试 {@link #isTransient}; 鉴权失败 / 模型不支持 / 本地超时 / 进程启动失败等
     * 确定性错误立即上抛, 重试无意义还会拖满 tick。退避耗尽后仍上抛, 交给 AppService 写
     * {@code last_error} + 跨 tick 会话级重试兜底。</p>
     */
    private String invokeCli(ConversationView view, String prompt) {
        RefineryProperties.Retry cfg = props.getRefine().getRetry();
        int maxAttempts = Math.max(1, cfg.getMaxAttempts());
        long backoffMs = cfg.getInitialBackoffMs();
        CliInvokeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return invokeOnce(view, prompt);
            } catch (CliInvokeException e) {
                last = e;
                if (attempt >= maxAttempts || !isTransient(e)) {
                    break;
                }
                long sleepMs = Math.min(backoffMs, cfg.getMaxBackoffMs());
                log.warn("refinery-refine-retry sourceId={} attempt={}/{} backoffMs={} reason={}",
                        view.getSourceId(), attempt, maxAttempts, sleepMs, e.getMessage());
                sleepBackoff(sleepMs);
                backoffMs = (long) (backoffMs * cfg.getMultiplier());
            }
        }
        throw new RefineException(
                "CLI invoke failed: " + last.getReason() + " " + last.getMessage(), last);
    }

    private String invokeOnce(ConversationView view, String prompt) {
        String model = props.getRefine().getModel();
        int timeout = props.getRefine().getTimeoutSeconds();
        if (model == null || model.trim().isEmpty()) {
            // 未配置评分专用模型: 走四参版, 与历史行为完全一致 (source 默认 CLI 模型)
            return cliInvoker.invokeSync(
                    view.getAgentType(), view.getWorkingDir(), prompt, timeout);
        }
        // 配了评分专用模型 (如 claude-haiku-4-5-20251001): 仅本次调用覆盖, 不影响主对话
        return cliInvoker.invokeSync(
                view.getAgentType(), view.getWorkingDir(), prompt, timeout, model);
    }

    /** 仅 CLI 非零退出且错误文本匹配瞬态 5xx/429 才重试; 其余 (超时/本地 IO 失败) 视为确定性. */
    private boolean isTransient(CliInvokeException e) {
        if (e.getReason() != CliInvokeException.Reason.NON_ZERO_EXIT) {
            return false;
        }
        String msg = e.getMessage();
        return msg != null && TRANSIENT_ERROR_PATTERN.matcher(msg).find();
    }

    private void sleepBackoff(long sleepMs) {
        try {
            sleeper.sleep(sleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RefineException("interrupted during refine backoff", ie);
        }
    }

    private String extractJson(String raw) {
        try {
            return LlmJsonExtractor.extractFirstObject(raw);
        } catch (IllegalArgumentException e) {
            throw new RefineException(e.getMessage(), e);
        }
    }

    private RefineResult parseResult(String jsonText) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(jsonText);
        } catch (IOException e) {
            throw new RefineException("invalid JSON: " + abbreviate(jsonText), e);
        }
        if (!root.isObject()) {
            throw new RefineException("expected JSON object, got " + root.getNodeType());
        }
        double score = numericField(root, "score");
        TtlCategory ttl = parseTtl(textField(root, "ttl_category"));
        String title = textField(root, "title");
        if (title.trim().isEmpty()) {
            throw new RefineException("missing required field: title");
        }
        List<String> signals = arrayField(root, "triggerSignals");
        RefinedContent content;
        try {
            content = new RefinedContent(
                    title,
                    signals,
                    textField(root, "triggerDescription"),
                    textField(root, "context"),
                    textField(root, "process"),
                    textField(root, "conclusion"));
            return new RefineResult(score, ttl, content);
        } catch (IllegalArgumentException e) {
            throw new RefineException("refine result domain validation failed: " + e.getMessage(), e);
        }
    }

    private TtlCategory parseTtl(String raw) {
        try {
            return TtlCategory.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new RefineException("invalid ttl_category: " + raw, e);
        }
    }

    private String sliceAndSanitize(List<ConversationTurn> turns) {
        int charBudget = props.getRefine().getTokenBudget() * CHARS_PER_TOKEN;
        List<String> formatted = new ArrayList<>();
        int used = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            ConversationTurn turn = turns.get(i);
            String line = formatLine(turn);
            if (used + line.length() > charBudget && !formatted.isEmpty()) {
                break;
            }
            formatted.add(0, line);
            used += line.length();
        }
        return sanitize(String.join("\n", formatted));
    }

    private String formatLine(ConversationTurn turn) {
        String role = turn.getRole();
        String content = turn.getContent();
        // assistant 消息可能是原始 stream-json (init / tool_use / thinking / base64 噪声,
        // 单条可达数百万字符), 先用 StreamOutputExtractor 提取纯文本结论再喂评分 —— 既消除
        // "Prompt is too long", 又让 LLM 看到真正的对话内容而非 JSON 噪声.
        // user 消息保持原样 (可能含用户粘贴的合法 JSON, 不应被 extract 误伤).
        if (TYPE_ASSISTANT.equalsIgnoreCase(role)) {
            content = outputExtractor.extractPlainText(content);
        }
        content = truncateSingle(content);
        return "[" + (role.isEmpty() ? "?" : role) + "] " + content;
    }

    /** 单条兜底截断: 即便 extractPlainText 漏网, 也保证单条不超 max-input-chars, 防 Prompt too long. */
    private String truncateSingle(String content) {
        int max = props.getEmbedding().getMaxInputChars();
        if (max <= 0 || content.length() <= max) {
            return content;
        }
        log.warn("refinery-message-truncated originalChars={} max={}", content.length(), max);
        return content.substring(0, max) + "…[truncated]";
    }

    private String sanitize(String text) {
        String out = text;
        for (String pattern : props.getPrivacy().getRedactPatterns()) {
            try {
                out = Pattern.compile(pattern).matcher(out).replaceAll(REDACTED);
            } catch (PatternSyntaxException e) {
                log.warn("refinery-redact-pattern-invalid pattern={} reason={}", pattern, e.getMessage());
            }
        }
        return out;
    }

    private String textField(JsonNode node, String name) {
        JsonNode v = node.get(name);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private double numericField(JsonNode node, String name) {
        JsonNode v = node.get(name);
        if (v == null || !v.isNumber()) {
            throw new RefineException("missing or non-numeric field: " + name);
        }
        return v.asDouble();
    }

    private List<String> arrayField(JsonNode node, String name) {
        JsonNode v = node.get(name);
        if (v == null || v.isNull()) {
            return Collections.emptyList();
        }
        if (!v.isArray()) {
            return Collections.singletonList(v.asText());
        }
        List<String> sink = new ArrayList<>();
        Iterator<JsonNode> it = v.elements();
        while (it.hasNext()) {
            JsonNode e = it.next();
            if (e != null && !e.isNull()) {
                sink.add(e.asText());
            }
        }
        return sink;
    }

    private String inlineFallback() {
        return "Score the conversation 0-1 and output strict JSON with fields: "
                + "score, ttl_category, title, triggerSignals, context, process, conclusion.\n"
                + "Messages:\n{{messages}}";
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
