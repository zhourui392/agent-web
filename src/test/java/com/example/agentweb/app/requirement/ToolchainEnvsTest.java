package com.example.agentweb.app.requirement;

import com.example.agentweb.app.requirement.RequirementProperties.Toolchain;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具链 env 解析（M3-lite）：按 repoUrl 正则匹配 toolchain 配置，首个命中生效。
 * 原由 M3 预构建镜像钉版本承担，容器转条件触发后以 run env 注入替代。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class ToolchainEnvsTest {

    private static final String LEGACY_REPO = "https://gitlab.example.com/platform/legacy-service.git";

    @Test
    public void resolve_should_return_empty_when_no_toolchains_or_blank_repo() {
        assertTrue(ToolchainEnvs.resolve(null, LEGACY_REPO).isEmpty());
        assertTrue(ToolchainEnvs.resolve(List.of(), LEGACY_REPO).isEmpty());
        assertTrue(ToolchainEnvs.resolve(List.of(toolchain("legacy", Map.of("JAVA_HOME", "jdk8"))), " ")
                .isEmpty());
    }

    @Test
    public void resolve_should_match_repo_pattern_case_insensitively() {
        List<Toolchain> toolchains = List.of(
                toolchain("legacy|tooling", Map.of("JAVA_HOME", "C:/Java/jdk1.8", "MAVEN_OPTS", "-Xmx1g")));

        Map<String, String> env = ToolchainEnvs.resolve(toolchains, LEGACY_REPO);

        assertEquals("C:/Java/jdk1.8", env.get("JAVA_HOME"));
        assertEquals("-Xmx1g", env.get("MAVEN_OPTS"));
    }

    @Test
    public void resolve_should_use_first_matching_toolchain_only() {
        List<Toolchain> toolchains = List.of(
                toolchain("legacy", Map.of("JAVA_HOME", "jdk8")),
                toolchain("example", Map.of("JAVA_HOME", "jdk21")));

        Map<String, String> env = ToolchainEnvs.resolve(toolchains, LEGACY_REPO);

        assertEquals("jdk8", env.get("JAVA_HOME"));
    }

    @Test
    public void resolve_should_return_empty_when_nothing_matches() {
        List<Toolchain> toolchains = List.of(toolchain("agent-web", Map.of("JAVA_HOME", "jdk21")));

        assertTrue(ToolchainEnvs.resolve(toolchains, LEGACY_REPO).isEmpty());
    }

    @Test
    public void resolve_should_skip_invalid_regex_and_blank_pattern_then_try_next() {
        List<Toolchain> toolchains = List.of(
                toolchain("[broken", Map.of("JAVA_HOME", "bad")),
                toolchain(" ", Map.of("JAVA_HOME", "blank")),
                toolchain("legacy", Map.of("JAVA_HOME", "jdk8")));

        Map<String, String> env = ToolchainEnvs.resolve(toolchains, LEGACY_REPO);

        assertEquals("jdk8", env.get("JAVA_HOME"));
    }

    @Test
    public void resolve_should_treat_matched_toolchain_without_env_as_empty() {
        List<Toolchain> toolchains = List.of(toolchain("legacy", null));

        assertTrue(ToolchainEnvs.resolve(toolchains, LEGACY_REPO).isEmpty());
    }

    private Toolchain toolchain(String pattern, Map<String, String> env) {
        Toolchain toolchain = new Toolchain();
        toolchain.setRepoPattern(pattern);
        if (env != null) {
            toolchain.getEnv().putAll(env);
        }
        return toolchain;
    }
}
