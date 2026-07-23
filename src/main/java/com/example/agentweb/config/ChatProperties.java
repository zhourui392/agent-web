package com.example.agentweb.config;

import com.example.agentweb.app.chatrun.ChatPromptSettings;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Chat-specific runtime switches.
 *
 * @author alex
 * @since 2026-06-24
 */
@Component
@ConfigurationProperties(prefix = "agent.chat")
@Getter
@Setter
public class ChatProperties implements ChatPromptSettings {

    /** 进程内活跃会话 LRU 最大条数，超出仍可从 SQLite 回源。 */
    private int sessionCacheMaxEntries = 1000;

    /** Whether to append the final-answer evidence instruction to normal chat turns. */
    private boolean finalAnswerInstructionEnabled = true;

    /** Short output contract appended to chat prompts so final answers keep reusable evidence. */
    private String finalAnswerInstruction = defaultFinalAnswerInstruction();

    private static String defaultFinalAnswerInstruction() {
        return "[最终回答要求]\n"
                + "如果本轮使用了数据库、日志、ES、Redis、配置中心或其他工具查询，请在最终回答中保留一个简短“证据摘要”小节，便于后续经验沉淀与召回。\n"
                + "要求：\n"
                + "1. 先给结论，避免只输出过程。\n"
                + "2. 证据摘要只写最终结论实际依赖的查询结果；中间试探、无效查询、0命中且未参与结论的查询不要写。\n"
                + "3. 每条证据控制在一行，最多 5 条。\n"
                + "4. 每条证据优先包含：数据源/表或服务、查询条件、命中行数、关键字段、错误码/状态。\n"
                + "5. 不要粘贴完整工具输出、SQL 大结果、日志大段或 JSON 原文。\n"
                + "6. 如果没有使用工具查询，或查询结果对结论无贡献，可以省略证据摘要。\n"
                + "推荐格式：\n"
                + "## 结论\n"
                + "...\n\n"
                + "## 证据摘要\n"
                + "| 来源 | 查询条件 | 命中/关键字段 | 支撑结论 |\n"
                + "| --- | --- | --- | --- |\n"
                + "| ... | ... | ... | ... |\n\n"
                + "## 处理建议\n"
                + "...";
    }
}
