package com.example.agentweb.domain.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Runtime 命令语义分类，把原始命令归入可安全展示的类别。
 *
 * <p>本对象不保留原始命令，避免后续事件、日志或 API 误透传其中的路径和 Secret。
 * 分类结果仅供前端展示测试/构建进度，不做命令拦截。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public final class RuntimeCommandPolicy {

    private static final int MAX_COMMAND_INSPECTION_LENGTH = 65_536;
    private static final Set<String> TEST_TOKENS = set(
            "test", "tests", "verify", "pytest", "vitest", "playwright",
            "surefire:test");
    private static final Set<String> BUILD_TOKENS = set(
            "package", "install", "build", "assemble", "compile");

    private RuntimeCommandPolicy() {
    }

    public static RuntimeCommandPolicy classify() {
        return new RuntimeCommandPolicy();
    }

    public RuntimeCommandAssessment assess(String rawCommand) {
        List<String> tokens = tokenize(rawCommand);
        if (isTest(tokens)) {
            return RuntimeCommandAssessment.of(RuntimeCommandClass.TEST);
        }
        if (isBuild(tokens)) {
            return RuntimeCommandAssessment.of(RuntimeCommandClass.BUILD);
        }
        if (containsProgram(tokens, "git")) {
            return RuntimeCommandAssessment.of(RuntimeCommandClass.GIT);
        }
        return RuntimeCommandAssessment.of(RuntimeCommandClass.SHELL);
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
}
