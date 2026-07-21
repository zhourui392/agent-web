package com.example.agentweb.infra.provider.shimo;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.adapter.requirement.FetchedDoc;
import com.example.agentweb.adapter.requirement.RequirementDocFetcher;
import com.example.agentweb.domain.shared.AgentType;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;

/**
 * 石墨文档拉取器。石墨没有开放 API,协议本质是"驱动 CLI agent 用浏览器抓取"——CLI 即适配器,
 * 本类只负责组指令、调 {@link AgentGateway} 单次执行、从输出提取标题。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class ShimoDocFetcher implements RequirementDocFetcher {

    private static final String SHIMO_HOST = "shimo.im";
    private static final String SHIMO_HOST_SUFFIX = ".shimo.im";
    private static final String TITLE_PREFIX = "# ";
    private static final String DEFAULT_TITLE = "未命名文档";

    private final AgentGateway agentGateway;
    private final AgentType agentType;
    private final String workingDir;

    public ShimoDocFetcher(AgentGateway agentGateway, AgentType agentType, String workingDir) {
        this.agentGateway = agentGateway;
        this.agentType = agentType;
        this.workingDir = workingDir;
    }

    /**
     * 判断 sourceRef 是否为石墨文档链接(shimo.im 主域或其子域)。
     *
     * @param sourceRef 文档 URL
     * @return 可识别为石墨链接时 true;null/空白/解析失败一律 false
     */
    @Override
    public boolean supports(String sourceRef) {
        if (sourceRef == null || sourceRef.trim().isEmpty()) {
            return false;
        }
        try {
            String host = new URI(sourceRef.trim()).getHost();
            return host != null && (SHIMO_HOST.equals(host) || host.endsWith(SHIMO_HOST_SUFFIX));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * 驱动 CLI agent 打开石墨文档并抓取正文 markdown。
     *
     * @param sourceRef 石墨文档 URL
     * @return 抓取结果(标题取首个 "# " 开头行,无则用默认标题)
     * @throws IllegalStateException agent 执行失败或被中断
     */
    @Override
    public FetchedDoc fetch(String sourceRef) {
        String instruction = buildInstruction(sourceRef);
        try {
            String output = agentGateway.runOnce(agentType, workingDir, instruction, null);
            return toFetchedDoc(output);
        } catch (IOException e) {
            throw new IllegalStateException("石墨文档拉取失败: " + sourceRef, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("石墨文档拉取被中断: " + sourceRef, e);
        }
    }

    /**
     * 组装抓取指令:约束 agent 只输出正文 markdown,首行为标题。
     *
     * @param sourceRef 石墨文档 URL
     * @return agent 指令
     */
    private String buildInstruction(String sourceRef) {
        return "请用浏览器打开以下石墨文档并提取正文:\n"
                + sourceRef + "\n"
                + "输出要求:\n"
                + "1. 只输出文档正文的 markdown,第一行必须是 `# <文档标题>`\n"
                + "2. 不要输出任何前后缀说明、过程描述或代码块围栏";
    }

    /**
     * 从 agent 输出提取标题并组装文档对象。
     *
     * @param output agent 原始输出;null 按空串处理
     * @return 拉取到的文档
     */
    private FetchedDoc toFetchedDoc(String output) {
        String markdown = output == null ? "" : output;
        return new FetchedDoc(extractTitle(markdown), markdown, Instant.now());
    }

    /**
     * 取首个 "# " 开头行作为标题。
     *
     * @param markdown 正文 markdown
     * @return 标题;无标题行返回默认标题
     */
    private String extractTitle(String markdown) {
        for (String line : markdown.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(TITLE_PREFIX)) {
                return trimmed.substring(TITLE_PREFIX.length()).trim();
            }
        }
        return DEFAULT_TITLE;
    }
}
