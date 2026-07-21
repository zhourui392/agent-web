package com.example.agentweb.app.requirement;

import com.example.agentweb.app.requirement.RequirementProperties.Toolchain;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 工具链 env 解析（M3-lite）：按 repoUrl 匹配 {@code agent.requirement.workspace.toolchains}，
 * 首个命中的配置项生效——容器转条件触发后，预构建镜像"钉工具链版本"的职责由 run env 注入替代
 * （遗留 JDK 8 仓库在 JDK 21 宿主跑 implement/fix run 的必要条件）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
public final class ToolchainEnvs {

    private ToolchainEnvs() {
    }

    /**
     * 解析仓库命中的工具链 env；无配置、repoUrl 空白或全不命中返回空 map（绝不返回 null）。
     *
     * @param toolchains 工具链配置表（可空）
     * @param repoUrl    工作区仓库地址
     * @return 命中项的 env（不可变视图）
     */
    public static Map<String, String> resolve(List<Toolchain> toolchains, String repoUrl) {
        if (toolchains == null || toolchains.isEmpty() || repoUrl == null || repoUrl.isBlank()) {
            return Collections.emptyMap();
        }
        for (Toolchain toolchain : toolchains) {
            if (matches(toolchain, repoUrl)) {
                Map<String, String> env = toolchain.getEnv();
                return env == null ? Collections.emptyMap() : Collections.unmodifiableMap(env);
            }
        }
        return Collections.emptyMap();
    }

    private static boolean matches(Toolchain toolchain, String repoUrl) {
        String pattern = toolchain.getRepoPattern();
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(repoUrl).find();
        } catch (PatternSyntaxException e) {
            log.warn("toolchain-pattern-invalid pattern={} reason={}", pattern, e.getMessage());
            return false;
        }
    }
}
