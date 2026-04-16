package com.example.agentweb;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 集成测试用的 CLI stub 配置助手：将 codex exec 指向一个跨平台的 echo 实现。
 * <p>Linux/macOS 上使用 {@code /bin/echo}，Windows 上使用 {@code cmd /c echo}。
 * stub 将 user message 前缀固定为 {@code "Echo "}，方便断言输出。</p>
 */
final class TestCliStub {

    private TestCliStub() {
    }

    static void register(DynamicPropertyRegistry registry) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            registry.add("agent.cli.codex.exec", () -> "cmd");
            registry.add("agent.cli.codex.args", () -> "/c,echo,Echo,${MESSAGE}");
        } else {
            registry.add("agent.cli.codex.exec", () -> "/bin/echo");
            registry.add("agent.cli.codex.args", () -> "Echo,${MESSAGE}");
        }
    }
}
