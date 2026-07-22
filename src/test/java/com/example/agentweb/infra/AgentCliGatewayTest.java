package com.example.agentweb.infra;

import com.example.agentweb.app.git.GitEnvResolver;
import com.example.agentweb.domain.git.GitConfigPolicy;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.cli.BuildContext;
import com.example.agentweb.infra.cli.CliDialect;
import com.example.agentweb.infra.git.GitAskpassScript;
import com.example.agentweb.infra.git.GitProcessEnvCustomizer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link AgentCliGateway} 的进程编排收尾时机：
 * <ul>
 *   <li>读到方言的 turn-end 标记即立即收尾，不再等子进程退出；</li>
 *   <li>无 turn-end 标记时退化为"进程退出 + 宽限期排空"，且不被孤儿子进程持有的 stdout 管道拖死。</li>
 * </ul>
 * <p>背景：codex 在 Windows 派生的后台子进程会继承 stdout 写端句柄，主进程退出后管道仍不闭合，
 * {@code readLine} 久阻约 100s。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
@Tag("process-integration")
class AgentCliGatewayTest {

    /** stub 脚本：主进程秒退，但后台子进程继承 stdout 并持有约这么多秒。 */
    private static final int ORPHAN_HOLD_SECONDS = 12;

    /** turn-end stub：打印标记后主进程仍前台存活这么多秒。 */
    private static final int TURN_END_ALIVE_SECONDS = 8;

    /** 进程退出兜底路径的收尾时限 —— 显著小于孤儿持有时长，证明二者已解耦。 */
    private static final long MAX_FINISH_MILLIS = 9000L;

    /** turn-end 提前收尾时限 —— 远小于进程存活时长，证明读到标记即收尾、未等进程退出。 */
    private static final long TURN_END_FINISH_MILLIS = 3000L;

    /** 用系统临时目录根作为进程 CWD：始终存在、无需清理，孤儿进程继承它也不影响测试。 */
    private static final String WORKING_DIR = System.getProperty("java.io.tmpdir");

    @Test
    void runStream_completesImmediatelyOnTurnEndMarker() throws Exception {
        List<String> cmd = writeTurnEndStub();
        AgentCliGateway gateway = newGateway(cmd, "TURN_END");

        List<String> chunks = new CopyOnWriteArrayList<String>();
        AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);

        long elapsed;
        try {
            elapsed = timed(() -> gateway.runStream(AgentType.CODEX, WORKING_DIR, "ignored",
                    "sess-turn", null, null, 0L, chunks::add, exitCode::set));
        } finally {
            deleteQuietly(cmd);
        }

        assertTrue(elapsed < TURN_END_FINISH_MILLIS,
                "读到 turn-end 标记应立即收尾、不等进程退出，实际耗时 " + elapsed + "ms");
        assertTrue(chunks.contains("TURN_END"), "turn-end 标记行应已通过 onChunk 送达，实际：" + chunks);
        assertEquals(0, exitCode.get(), "turn-end 收尾退出码应为 0");
    }

    @Test
    void runStream_finishesWhenProcessExits_evenIfOrphanHoldsStdout() throws Exception {
        List<String> cmd = writeOrphanStub();
        AgentCliGateway gateway = newGateway(cmd, null);

        List<String> chunks = new CopyOnWriteArrayList<String>();
        AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);

        long elapsed;
        try {
            elapsed = timed(() -> gateway.runStream(AgentType.CODEX, WORKING_DIR, "ignored",
                    "sess-orphan", null, null, 0L, chunks::add, exitCode::set));
        } finally {
            deleteQuietly(cmd);
        }

        assertTrue(elapsed < MAX_FINISH_MILLIS,
                "runStream 应在进程退出后数秒内收尾，实际耗时 " + elapsed + "ms");
        assertTrue(chunks.contains("line1") && chunks.contains("line2"),
                "进程退出前产出的行应已通过 onChunk 送达，实际：" + chunks);
        assertEquals(0, exitCode.get(), "退出码应为 0");
    }

    @Test
    void runOnce_finishesWhenProcessExits_evenIfOrphanHoldsStdout() throws Exception {
        List<String> cmd = writeOrphanStub();
        AgentCliGateway gateway = newGateway(cmd, null);

        final String[] output = new String[1];
        long elapsed;
        try {
            elapsed = timed(() ->
                    output[0] = gateway.runOnce(AgentType.CODEX, WORKING_DIR, "ignored"));
        } finally {
            deleteQuietly(cmd);
        }

        assertTrue(elapsed < MAX_FINISH_MILLIS,
                "runOnce 应在进程退出后数秒内返回，实际耗时 " + elapsed + "ms");
        assertTrue(output[0].contains("line1") && output[0].contains("line2"),
                "应返回进程退出前排空的输出，实际：" + output[0]);
    }

    @Test
    void runStream_rejectsSecondLiveProcessForSameSession() throws Exception {
        List<String> cmd = writeLongRunningStub();
        AgentCliGateway gateway = newGateway(cmd, null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> first = executor.submit(() -> {
            try {
                gateway.runStream(AgentType.CODEX, WORKING_DIR, "ignored",
                        "same-session", null, null, 0L, chunk -> { }, code -> { });
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        try {
            long deadline = System.currentTimeMillis() + 3000L;
            while (!gateway.isRunning("same-session") && System.currentTimeMillis() < deadline) {
                Thread.yield();
            }
            assertTrue(gateway.isRunning("same-session"), "first process should be registered");

            assertThrows(IllegalStateException.class, () ->
                    gateway.runStream(AgentType.CODEX, WORKING_DIR, "ignored",
                            "same-session", null, null, 0L, chunk -> { }, code -> { }));
        } finally {
            gateway.stopStream("same-session");
            first.get(3, TimeUnit.SECONDS);
            executor.shutdownNow();
            deleteQuietly(cmd);
        }
    }

    @Test
    void runStream_killsProcessWhenOutputExceedsConfiguredLimit() throws Exception {
        List<String> cmd = writeOutputFloodStub();
        AgentCliGateway gateway = newGateway(cmd, null, 128L);
        List<String> chunks = new CopyOnWriteArrayList<>();
        try {
            long elapsed = timed(() -> gateway.runStream(AgentType.CODEX, WORKING_DIR, "ignored",
                    "output-limit", null, null, 0L, chunks::add, code -> { }));

            assertTrue(elapsed < 3000L, "output limit should terminate process promptly");
            assertTrue(chunks.contains("[output-limit]"), "client should receive explicit output-limit event");
        } finally {
            deleteQuietly(cmd);
        }
    }

    // ── helpers ──

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private long timed(ThrowingRunnable action) throws Exception {
        long t0 = System.currentTimeMillis();
        action.run();
        return System.currentTimeMillis() - t0;
    }

    /** {@code timeout-seconds=0}：复现生产配置，正是这条无总超时路径会被孤儿管道久阻。 */
    private AgentCliGateway newGateway(List<String> cmd, String turnEndMarker) {
        return newGateway(cmd, turnEndMarker, 10L * 1024L * 1024L);
    }

    private AgentCliGateway newGateway(List<String> cmd, String turnEndMarker, long maxOutputBytes) {
        AgentCliProperties props = new AgentCliProperties();
        AgentCliProperties.Client codex = props.getCodex();
        codex.setExec(cmd.get(0));
        codex.setStdin(false);
        codex.setTimeoutSeconds(0);
        codex.setStdoutDrainGraceMs(200L);
        codex.setMaxOutputBytes(maxOutputBytes);
        return new AgentCliGateway(props, new EnvProperties(), noopGitCustomizer(),
                Collections.singletonList(passthroughDialect(cmd, turnEndMarker)));
    }

    /**
     * 不注入任何 git 身份的 customizer：本测试一律以 userId=null 调用（9 参 default 重载），
     * resolver 对 null userId 判系统默认 → 返回空规格 → apply 提前返回，不触达 repo/cipher。
     */
    private GitProcessEnvCustomizer noopGitCustomizer() {
        GitEnvResolver resolver = new GitEnvResolver(null, new GitConfigPolicy(), null);
        return new GitProcessEnvCustomizer(resolver, new GitAskpassScript());
    }

    /**
     * 写一个"主进程立即退出、但后台子进程继承 stdout 并持有约 {@value #ORPHAN_HOLD_SECONDS}s"的 stub 脚本，
     * 返回启动该脚本的命令 token 列表。
     */
    private List<String> writeOrphanStub() {
        if (isWindows()) {
            return writeStub(".cmd", Arrays.asList(
                    "@echo off",
                    "echo line1",
                    "echo line2",
                    "start /b cmd /c ping -n " + (ORPHAN_HOLD_SECONDS + 1) + " 127.0.0.1 >nul"));
        }
        return writeStub(".sh", Arrays.asList(
                "#!/bin/sh",
                "echo line1",
                "echo line2",
                "sleep " + ORPHAN_HOLD_SECONDS + " &"));
    }

    /**
     * 写一个"打印 turn-end 标记后主进程仍前台存活约 {@value #TURN_END_ALIVE_SECONDS}s"的 stub 脚本，
     * 返回启动该脚本的命令 token 列表。
     */
    private List<String> writeTurnEndStub() {
        if (isWindows()) {
            return writeStub(".cmd", Arrays.asList(
                    "@echo off",
                    "echo TURN_END",
                    "ping -n " + (TURN_END_ALIVE_SECONDS + 1) + " 127.0.0.1 >nul"));
        }
        return writeStub(".sh", Arrays.asList(
                "#!/bin/sh",
                "echo TURN_END",
                "sleep " + TURN_END_ALIVE_SECONDS));
    }

    private List<String> writeLongRunningStub() {
        if (isWindows()) {
            return writeStub(".cmd", Arrays.asList(
                    "@echo off",
                    "ping -n 10 127.0.0.1 >nul"));
        }
        return writeStub(".sh", Arrays.asList(
                "#!/bin/sh",
                "sleep 9"));
    }

    private List<String> writeOutputFloodStub() {
        if (isWindows()) {
            return writeStub(".cmd", Arrays.asList(
                    "@echo off",
                    "for /L %%i in (1,1,200) do @echo 012345678901234567890123456789",
                    "ping -n 6 127.0.0.1 >nul"));
        }
        return writeStub(".sh", Arrays.asList(
                "#!/bin/sh",
                "i=0",
                "while [ $i -lt 200 ]; do echo 012345678901234567890123456789; i=$((i + 1)); done",
                "sleep 5"));
    }

    private List<String> writeStub(String suffix, List<String> lines) {
        try {
            Path script = Files.createTempFile("agent-cli-stub", suffix);
            Files.write(script, lines, StandardCharsets.US_ASCII);
            return isWindows()
                    ? Arrays.asList("cmd", "/c", script.toString())
                    : Arrays.asList("sh", script.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 删除 stub 脚本文件，失败忽略（不影响断言，文件在系统临时目录）。 */
    private void deleteQuietly(List<String> cmd) {
        try {
            Files.deleteIfExists(Paths.get(cmd.get(cmd.size() - 1)));
        } catch (IOException ignore) {
            // best-effort
        }
    }

    /**
     * 测试方言：{@code buildCommand} 直通固定命令；{@code isTurnEnd} 把指定标记行识别为回合结束。
     * resume / 归一化非本测试关注点。
     *
     * @param command       子进程命令 token
     * @param turnEndMarker 被 {@code isTurnEnd} 识别为回合结束的整行文本；为 null 表示永不命中
     */
    private CliDialect passthroughDialect(final List<String> command, final String turnEndMarker) {
        return new CliDialect() {
            @Override
            public AgentType type() {
                return AgentType.CODEX;
            }

            @Override
            public List<String> buildCommand(BuildContext ctx) {
                return command;
            }

            @Override
            public String extractResumeId(String stdoutLine) {
                return null;
            }

            @Override
            public List<String> normalizeChunk(String stdoutLine) {
                return Collections.singletonList(stdoutLine);
            }

            @Override
            public boolean isTurnEnd(String stdoutLine) {
                return turnEndMarker != null && turnEndMarker.equals(stdoutLine);
            }
        };
    }
}
