package com.example.agentweb.infra.issuelog.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * issue-log 一键沉淀链路的配置,通过 {@code agent.issue-log.*} 绑定。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
@Component
@ConfigurationProperties(prefix = "agent.issue-log")
@Getter
@Setter
public class IssueLogProperties {

    /** issue-log 根目录,相对工作目录。默认 {@code docs/issue-log}。 */
    private String docsDir = "docs/issue-log";

    /** issue 文件子目录名,相对 {@link #docsDir}。默认 {@code issue}。 */
    private String issuesSubdir = "issue";

    /** INDEX 文件名(大小写敏感)。默认 {@code INDEX.md}。 */
    private String indexFile = "INDEX.md";

    /** {@link #docsDir} 不存在时自动初始化目录与 INDEX 表头。默认 {@code true}。 */
    private boolean autoInit = true;

    /** issue 文件名 slug 最大字符数,超出截断。默认 {@code 50}。 */
    private int slugMaxLength = 50;

    private Refine refine = new Refine();

    private Backfill backfill = new Backfill();

    private Merge merge = new Merge();

    private Dedup dedup = new Dedup();

    /**
     * 交互式"沉淀为 issue-log"保存前的查重闸门配置,通过 {@code agent.issue-log.dedup.*} 绑定。
     */
    @Getter
    @Setter
    public static class Dedup {

        /** 是否在交互式保存前执行查重。关闭则保存直接落盘(旧行为)。默认 {@code true}。 */
        private boolean enabled = true;

        /** 保存前查重单次 CLI 调用的硬超时。默认 120 秒。 */
        private long timeoutSeconds = 120L;
    }

    /** LLM 精炼草稿相关配置。 */
    @Getter
    @Setter
    public static class Refine {

        /** 是否启用 LLM 精炼。关闭则直接走启发式 DraftBuilder。默认 {@code true}。 */
        private boolean enabled = true;

        /** 单次精炼 CLI 调用的硬超时。默认 180 秒。 */
        private long timeoutSeconds = 180L;

        /** prompt 模板 classpath 资源路径。 */
        private String promptTemplate = "classpath:/issue-log-refine-prompt.md";
    }

    /**
     * 历史诊断任务批量回填为 issue-log 经验的相关配置,通过 {@code agent.issue-log.backfill.*} 绑定。
     */
    @Getter
    @Setter
    public static class Backfill {

        /** 是否启用定时回填。默认 {@code false},仅产候选不归档。 */
        private boolean enabled = false;

        /** 定时回填的轮询间隔。默认 86400 秒(每天一次)。 */
        private int intervalSeconds = 86400;

        /** 单轮最多处理的诊断任务数,避免单次 tick 跑太久。默认 20。 */
        private int maxPerRun = 20;

        /** 查重比对单次 CLI 调用的硬超时。默认 120 秒。 */
        private long matchTimeoutSeconds = 120L;

        /**
         * 飞书来源诊断 verdict 未标注时的等待窗口(小时),基准 {@code DiagnoseTask.finishedAt}。
         * 超过该窗口仍未标注则视为允许沉淀,进主回填池兜底。默认 48 小时。
         */
        private long feedbackWaitHours = 48L;
    }

    /**
     * 审核通过时调 agent 把候选合并进既有条目的相关配置,通过 {@code agent.issue-log.merge.*} 绑定。
     */
    @Getter
    @Setter
    public static class Merge {

        /** 是否对 MERGE 候选启用 agent 合并。关闭则一律确定性新建。默认 {@code true}。 */
        private boolean enabled = true;

        /** 单次合并 CLI 调用的硬超时。默认 180 秒。 */
        private long timeoutSeconds = 180L;

        /** prompt 模板 classpath 资源路径。 */
        private String promptTemplate = "classpath:/issue-log-merge-prompt.md";
    }
}
