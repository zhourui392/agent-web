package com.example.agentweb.domain.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Runtime 命令语义分类与高影响操作拒绝策略。
 *
 * <p>类型化 Operation 授权只能由 Workbench Operation 流程产生。普通 Agent 命令无论来自
 * Prompt、回复还是 Shell 包装，都不能获得 commit、push、deploy 或 production-write 权限。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public final class RuntimeCommandPolicy {

    private static final int MAX_COMMAND_INSPECTION_LENGTH = 65_536;
    private static final Set<String> GIT_COMMIT_COMMANDS = set("commit");
    private static final Set<String> GIT_PUSH_COMMANDS = set("push", "send-pack");
    private static final Set<String> KUBECTL_MUTATIONS = set(
            "apply", "create", "delete", "edit", "patch", "replace",
            "rollout", "scale", "set", "taint", "annotate", "label");
    private static final Set<String> HELM_MUTATIONS = set(
            "install", "upgrade", "uninstall", "rollback");
    private static final Set<String> DOCKER_MUTATIONS = set(
            "push", "run", "restart", "start", "stop", "rm");
    private static final Set<String> DOCKER_COMPOSE_MUTATIONS = set(
            "up", "down", "restart", "start", "stop", "rm", "push");
    private static final Set<String> SERVICE_MUTATIONS = set(
            "start", "stop", "restart", "reload", "enable", "disable");
    private static final Set<String> SQL_MUTATIONS = set(
            "insert", "update", "delete", "alter", "drop", "create",
            "truncate", "grant", "revoke", "merge", "replace");
    private static final Set<String> TEST_TOKENS = set(
            "test", "tests", "verify", "pytest", "vitest", "playwright",
            "surefire:test");
    private static final Set<String> BUILD_TOKENS = set(
            "package", "install", "build", "assemble", "compile");
    private static final List<List<String>> OPAQUE_SHELL_WRAPPER_PREFIXES =
            immutablePrefixes(
                    prefix("bash", "-lc"),
                    prefix("bash", "-c"),
                    prefix("sh", "-lc"),
                    prefix("sh", "-c"),
                    prefix("zsh", "-lc"),
                    prefix("zsh", "-c"),
                    prefix("dash", "-c"),
                    prefix("ksh", "-c"),
                    prefix("cmd"),
                    prefix("cmd.exe"),
                    prefix("powershell"),
                    prefix("powershell.exe"),
                    prefix("pwsh"),
                    prefix("pwsh.exe"),
                    prefix("bash.exe", "-lc"),
                    prefix("bash.exe", "-c"),
                    prefix("sh.exe", "-lc"),
                    prefix("sh.exe", "-c"));

    private final List<RuntimeCommandPrefix> forbiddenPrefixes;

    private RuntimeCommandPolicy(List<RuntimeCommandPrefix> forbiddenPrefixes) {
        this.forbiddenPrefixes = Collections.unmodifiableList(
                new ArrayList<RuntimeCommandPrefix>(forbiddenPrefixes));
    }

    public static RuntimeCommandPolicy platformDefault() {
        List<RuntimeCommandPrefix> prefixes = new ArrayList<RuntimeCommandPrefix>();
        prefixes.add(RuntimeCommandPrefix.of(
                RuntimeHighImpactOperation.GIT_COMMIT, "git", "commit"));
        prefixes.add(RuntimeCommandPrefix.of(
                RuntimeHighImpactOperation.GIT_PUSH, "git", "push"));
        prefixes.add(RuntimeCommandPrefix.of(
                RuntimeHighImpactOperation.GIT_PUSH, "git", "send-pack"));
        addDeployPrefixes(prefixes, "kubectl", KUBECTL_MUTATIONS);
        addDeployPrefixes(prefixes, "helm", HELM_MUTATIONS);
        addDeployPrefixes(prefixes, "docker", DOCKER_MUTATIONS);
        addDeployPrefixes(prefixes, "docker-compose", DOCKER_COMPOSE_MUTATIONS);
        addDeployPrefixes(prefixes, "systemctl", SERVICE_MUTATIONS);
        prefixes.add(RuntimeCommandPrefix.of(
                RuntimeHighImpactOperation.LOCAL_DEPLOY,
                "terraform", "apply"));
        prefixes.add(RuntimeCommandPrefix.of(
                RuntimeHighImpactOperation.LOCAL_DEPLOY,
                "terraform", "destroy"));
        prefixes.add(RuntimeCommandPrefix.of(
                RuntimeHighImpactOperation.LOCAL_DEPLOY,
                "ansible-playbook"));
        prefixes.add(RuntimeCommandPrefix.of(
                RuntimeHighImpactOperation.LOCAL_DEPLOY, "deploy"));
        prefixes.add(RuntimeCommandPrefix.of(
                RuntimeHighImpactOperation.LOCAL_DEPLOY, "deploy.sh"));
        prefixes.add(RuntimeCommandPrefix.of(
                RuntimeHighImpactOperation.LOCAL_DEPLOY, "deploy.ps1"));
        prefixes.add(RuntimeCommandPrefix.of(
                RuntimeHighImpactOperation.LOCAL_DEPLOY, "release"));
        prefixes.add(RuntimeCommandPrefix.of(
                RuntimeHighImpactOperation.LOCAL_DEPLOY, "release.sh"));
        return new RuntimeCommandPolicy(prefixes);
    }

    public List<RuntimeCommandPrefix> getForbiddenPrefixes() {
        return forbiddenPrefixes;
    }

    /**
     * 返回 Codex 无法安全拆分脚本时必须整体拒绝的 Shell 包装前缀。
     *
     * <p>线性、可安全解析的脚本会由 Codex 拆为独立命令再逐条匹配；使用变量、重定向、
     * 通配符、替换或控制流的脚本保留包装器形态，因此必须 fail closed，避免高影响操作
     * 藏在不可解释脚本中绕过类型化 Operation。</p>
     */
    public List<List<String>> getOpaqueShellWrapperPrefixes() {
        return OPAQUE_SHELL_WRAPPER_PREFIXES;
    }

    public RuntimeCommandAssessment assess(String rawCommand) {
        List<String> tokens = tokenize(rawCommand);
        RuntimeHighImpactOperation gitOperation = gitOperation(tokens);
        if (gitOperation != null) {
            return RuntimeCommandAssessment.blocked(
                    RuntimeCommandClass.GIT, gitOperation);
        }
        if (isDeploy(tokens)) {
            return RuntimeCommandAssessment.blocked(
                    RuntimeCommandClass.DEPLOY,
                    RuntimeHighImpactOperation.LOCAL_DEPLOY);
        }
        if (isProductionWrite(tokens)) {
            return RuntimeCommandAssessment.blocked(
                    RuntimeCommandClass.PRODUCTION_WRITE,
                    RuntimeHighImpactOperation.PRODUCTION_WRITE);
        }
        if (isTest(tokens)) {
            return RuntimeCommandAssessment.allowed(RuntimeCommandClass.TEST);
        }
        if (isBuild(tokens)) {
            return RuntimeCommandAssessment.allowed(RuntimeCommandClass.BUILD);
        }
        if (containsProgram(tokens, "git")) {
            return RuntimeCommandAssessment.allowed(RuntimeCommandClass.GIT);
        }
        return RuntimeCommandAssessment.allowed(RuntimeCommandClass.SHELL);
    }

    private static void addDeployPrefixes(
            List<RuntimeCommandPrefix> prefixes, String program,
            Set<String> subcommands) {
        for (String subcommand : subcommands) {
            prefixes.add(RuntimeCommandPrefix.of(
                    RuntimeHighImpactOperation.LOCAL_DEPLOY,
                    program, subcommand));
        }
    }

    private RuntimeHighImpactOperation gitOperation(List<String> tokens) {
        for (int index = 0; index < tokens.size(); index++) {
            if (!"git".equals(program(tokens.get(index)))) {
                continue;
            }
            String subcommand = gitSubcommand(tokens, index + 1);
            if (GIT_COMMIT_COMMANDS.contains(subcommand)) {
                return RuntimeHighImpactOperation.GIT_COMMIT;
            }
            if (GIT_PUSH_COMMANDS.contains(subcommand)) {
                return RuntimeHighImpactOperation.GIT_PUSH;
            }
        }
        return null;
    }

    private String gitSubcommand(List<String> tokens, int start) {
        int index = start;
        while (index < tokens.size()) {
            String token = tokens.get(index);
            if ("-c".equals(token) || "--config-env".equals(token)
                    || "--git-dir".equals(token) || "--work-tree".equals(token)
                    || "--namespace".equals(token)) {
                index += 2;
                continue;
            }
            if (token.startsWith("-")) {
                index++;
                continue;
            }
            return token;
        }
        return "";
    }

    private boolean isDeploy(List<String> tokens) {
        for (int index = 0; index < tokens.size(); index++) {
            String program = program(tokens.get(index));
            String next = next(tokens, index);
            if ("kubectl".equals(program) && KUBECTL_MUTATIONS.contains(next)
                    || "helm".equals(program) && HELM_MUTATIONS.contains(next)
                    || "docker-compose".equals(program)
                    && DOCKER_COMPOSE_MUTATIONS.contains(next)
                    || "systemctl".equals(program) && containsAfter(
                    tokens, index, SERVICE_MUTATIONS)
                    || "service".equals(program) && containsAfter(
                    tokens, index, SERVICE_MUTATIONS)
                    || "terraform".equals(program)
                    && ("apply".equals(next) || "destroy".equals(next))
                    || "ansible-playbook".equals(program)) {
                return true;
            }
            if ("docker".equals(program)) {
                if ("compose".equals(next)
                        && index + 2 < tokens.size()
                        && DOCKER_COMPOSE_MUTATIONS.contains(
                        tokens.get(index + 2))) {
                    return true;
                }
                if (DOCKER_MUTATIONS.contains(next)) {
                    return true;
                }
            }
            if (program.matches("(?i)(deploy|release)(\\.sh|\\.ps1|\\.cmd|\\.bat)?")) {
                return true;
            }
        }
        return false;
    }

    private boolean isProductionWrite(List<String> tokens) {
        for (int index = 0; index < tokens.size(); index++) {
            String program = program(tokens.get(index));
            if (("psql".equals(program) || "mysql".equals(program)
                    || "mongosh".equals(program) || "redis-cli".equals(program))
                    && containsAfter(tokens, index, SQL_MUTATIONS)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTest(List<String> tokens) {
        if (containsProgram(tokens, "pytest") || containsProgram(tokens, "vitest")
                || containsProgram(tokens, "playwright")) {
            return true;
        }
        for (int index = 0; index < tokens.size(); index++) {
            String program = program(tokens.get(index));
            if (("mvn".equals(program) || "mvnw".equals(program)
                    || "gradle".equals(program) || "gradlew".equals(program)
                    || "npm".equals(program) || "pnpm".equals(program)
                    || "yarn".equals(program) || "cargo".equals(program)
                    || "go".equals(program))
                    && containsAfter(tokens, index, TEST_TOKENS)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBuild(List<String> tokens) {
        for (int index = 0; index < tokens.size(); index++) {
            String program = program(tokens.get(index));
            if (("mvn".equals(program) || "mvnw".equals(program)
                    || "gradle".equals(program) || "gradlew".equals(program)
                    || "npm".equals(program) || "pnpm".equals(program)
                    || "yarn".equals(program) || "cargo".equals(program)
                    || "go".equals(program))
                    && containsAfter(tokens, index, BUILD_TOKENS)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsProgram(List<String> tokens, String expected) {
        for (String token : tokens) {
            if (expected.equals(program(token))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAfter(
            List<String> tokens, int index, Set<String> expected) {
        for (int cursor = index + 1; cursor < tokens.size(); cursor++) {
            if (expected.contains(tokens.get(cursor))) {
                return true;
            }
        }
        return false;
    }

    private String next(List<String> tokens, int index) {
        return index + 1 < tokens.size() ? tokens.get(index + 1) : "";
    }

    private String program(String token) {
        String normalized = token.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        String basename = separator < 0
                ? normalized : normalized.substring(separator + 1);
        if (basename.endsWith(".exe")) {
            return basename.substring(0, basename.length() - 4);
        }
        return basename;
    }

    private List<String> tokenize(String rawCommand) {
        if (rawCommand == null || rawCommand.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String bounded = rawCommand.length() > MAX_COMMAND_INSPECTION_LENGTH
                ? rawCommand.substring(0, MAX_COMMAND_INSPECTION_LENGTH)
                : rawCommand;
        String normalized = bounded.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s;|&()<>\\\"'`]+", " ");
        String[] split = normalized.trim().split(" +");
        if (split.length == 1 && split[0].isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(split);
    }

    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(Arrays.asList(values)));
    }

    private static List<String> prefix(String... values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }

    @SafeVarargs
    private static List<List<String>> immutablePrefixes(
            List<String>... values) {
        return Collections.unmodifiableList(
                Arrays.asList(values));
    }
}
