package com.example.agentweb.domain.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runtime 命令分类策略测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeCommandPolicyTest {

    private final RuntimeCommandPolicy policy = RuntimeCommandPolicy.classify();

    @Test
    void classifiesGitAndReadCommandsAsGitWithoutBlocking() {
        assertEquals(RuntimeCommandClass.GIT,
                policy.assess("git status --short").getCommandClass());
        assertEquals(RuntimeCommandClass.GIT,
                policy.assess("git commit -m reviewed").getCommandClass());
        assertEquals(RuntimeCommandClass.GIT,
                policy.assess("git push origin master").getCommandClass());
    }

    @Test
    void classifiesTestBuildAndGeneralCommandsWithoutLeakingCommandText() {
        assertEquals(RuntimeCommandClass.TEST,
                policy.assess("./mvnw -q test").getCommandClass());
        assertEquals(RuntimeCommandClass.TEST,
                policy.assess("npm run test -- --run").getCommandClass());
        assertEquals(RuntimeCommandClass.BUILD,
                policy.assess("npm run build").getCommandClass());
        assertEquals(RuntimeCommandClass.SHELL,
                policy.assess("rg --files src").getCommandClass());
    }
}
