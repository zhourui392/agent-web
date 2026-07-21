package com.example.agentweb.app.requirement;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求线配置族 {@code agent.requirement.*}，默认全关（M0 总开关 enabled=false 时
 * Controller/Advice 不装配，现有功能零影响）。放 app 层：app 不得依赖 infra 具体类（ArchUnit A4）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Component
@ConfigurationProperties(prefix = "agent.requirement")
@Getter
@Setter
public class RequirementProperties {

    private boolean enabled = false;

    /** 需求线 run 使用的 CLI agent（AgentType 名，全局一个，M2 暂不分 run 形态）。 */
    private String runAgentType = "CLAUDE";

    private Quota quota = new Quota();

    private Intake intake = new Intake();

    private Workspace workspace = new Workspace();

    private Plan plan = new Plan();

    private Implement implement = new Implement();

    private Delivery delivery = new Delivery();

    private Verify verify = new Verify();

    @Getter
    @Setter
    public static class Quota {

        /** 每用户并发活跃需求数（非终态计数）；非正数不设限。 */
        private int maxActivePerUser = 5;

        /** 每需求并发 run 数（M2 计划门起启用，执行点在发起 run 的编排公共前置）。 */
        private int maxRunsPerRequirement = 2;
    }

    @Getter
    @Setter
    public static class Workspace {

        /** mirror/worktree 落盘根目录（{root}/mirrors + {root}/worktrees，detailed-design §2.4）。 */
        private String root = "data/req-workspaces";

        /** M1 默认目标仓（需求暂不携带仓库信息，M2 GitLab issue 接入后按需求覆盖）。 */
        private String repoUrl = "";

        /** 工作区闲置 TTL（小时），过期进清理流程（保留态需求除外）。 */
        private int ttlHours = 72;

        /** TTL 清理调度 cron；秒级字段对齐 Spring @Scheduled 六段式。 */
        private String cleanupCron = "0 40 4 * * *";

        /** 端口租约池（含两端），挂需求的 run 经 AGENT_DEV_PORT 消费。 */
        private int portRangeStart = 42000;

        private int portRangeEnd = 42199;

        /** 按仓库工具链 env 注入（M3-lite，容器镜像钉版本的轻量替代），repoUrl 正则匹配，首个命中生效。 */
        private List<Toolchain> toolchains = new ArrayList<>();

        /** 磁盘剩余空间告警阈值（GB），低于即 notifyDuty；0 = 关闭（M3-lite，替代容器 cgroup 配额）。 */
        private int minFreeDiskGb = 0;

        /** 磁盘监控调度 cron（六段式），错开整点减少与其它调度扎堆。 */
        private String diskCheckCron = "0 17 * * * *";
    }

    @Getter
    @Setter
    public static class Toolchain {

        /** repoUrl 匹配正则（大小写不敏感、部分匹配）；空白或非法正则跳过本条。 */
        private String repoPattern = "";

        /** 命中后并入 run 的环境变量（如为遗留仓库设置 JDK 8 的 JAVA_HOME）。 */
        private Map<String, String> env = new LinkedHashMap<>();
    }

    @Getter
    @Setter
    public static class Intake {

        /** GITLAB_ISSUE 接入 owner 映射失败时的回落接待人；回落人也未配置则拒收该 issue（§3.7）。 */
        private String defaultOwner = "";
    }

    @Getter
    @Setter
    public static class Plan {

        /** 计划 run 硬超时（分钟）。 */
        private int runTimeoutMinutes = 30;

        /** 计划 run 的工作目录（无工作区阶段的落脚点，编排负责确保存在）。 */
        private String workingDir = "data/req-workspaces/plan-runs";
    }

    @Getter
    @Setter
    public static class Implement {

        /** 实现 run 硬超时（分钟）。 */
        private int runTimeoutMinutes = 240;
    }

    @Getter
    @Setter
    public static class Delivery {

        /** 提供方选择（master-plan §3.5），M2 仅 gitlab。 */
        private String provider = "gitlab";

        /** GitLab REST API base。 */
        private String gitlabBaseUrl = "";

        /** 系统默认账号用户名（token 禁落 yml：app_setting 加密存储，env AGENT_GITLAB_DEFAULT_TOKEN 优先）。 */
        private String defaultAccountUsername = "";

        /** MR 目标分支。 */
        private String defaultTargetBranch = "master";

        /** 平台外链 base（commit trailer 回链用）；空则回链退化为 requirement:<id> 标识。 */
        private String platformBaseUrl = "";

        /** webhook secret（yml 仅放 env 占位符 AGENT_SCM_WEBHOOK_SECRET，禁写死；空 = 拒绝所有 webhook）。 */
        private String webhookSecret = "";

        /** webhook 来源 CIDR 白名单，空 = 仅 secret 校验。 */
        private List<String> webhookAllowedCidrs = new ArrayList<>();

        /** issue 接入 agent 开发流程的 GitLab 标签名；空白回落 agent-dev。 */
        private String issueIntakeLabel = "agent-dev";
    }

    @Getter
    @Setter
    public static class Verify {

        /** L1 长 run 硬超时（小时）。 */
        private int runTimeoutHours = 4;

        /** 连续失败轮次熔断硬上限（资源兜底，随规模可调；不是方法论口径）。 */
        private int maxConsecutiveFailedRounds = 3;

        /** 同因连续失败熔断硬上限（资源兜底，随规模可调；不是方法论口径）。 */
        private int maxSameVerdictFailures = 2;
    }
}
