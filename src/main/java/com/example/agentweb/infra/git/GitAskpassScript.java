package com.example.agentweb.infra.git;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 跨平台 {@code GIT_ASKPASS} 脚本：从子进程 env（{@code AGENT_GIT_USERNAME}/{@code AGENT_GIT_PASSWORD}）
 * 回吐凭证给 git。脚本内容静态（密钥只在 env，不写脚本），故全局复用一份，懒创建。
 *
 * <p>git 调用 askpass 时把提示语作为唯一参数传入（"Username for ..." / "Password for ..."），
 * 脚本据此选择回吐用户名还是密码。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Component
@Slf4j
public class GitAskpassScript {

    private static final String WINDOWS_SCRIPT_NAME = "agent-web-git-askpass.cmd";
    private static final String UNIX_SCRIPT_NAME = "agent-web-git-askpass.sh";

    private final Object lock = new Object();
    private volatile Path scriptPath;

    /**
     * 确保 askpass 脚本存在，返回其绝对路径。
     *
     * @return 脚本路径
     * @throws IOException 写脚本失败
     */
    public String ensureScript() throws IOException {
        Path cached = scriptPath;
        if (cached != null && Files.exists(cached)) {
            return cached.toString();
        }
        synchronized (lock) {
            if (scriptPath != null && Files.exists(scriptPath)) {
                return scriptPath.toString();
            }
            scriptPath = writeScript();
            log.info("git-askpass-script-ready path={}", scriptPath);
            return scriptPath.toString();
        }
    }

    private Path writeScript() throws IOException {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"));
        return isWindows() ? writeWindowsScript(dir) : writeUnixScript(dir);
    }

    private Path writeWindowsScript(Path dir) throws IOException {
        Path path = dir.resolve(WINDOWS_SCRIPT_NAME);
        String content = "@echo off\r\n"
                + "echo %~1 | findstr /I \"sername\" >nul\r\n"
                + "if %errorlevel%==0 ( echo %AGENT_GIT_USERNAME% ) else ( echo %AGENT_GIT_PASSWORD% )\r\n";
        Files.write(path, content.getBytes(StandardCharsets.US_ASCII));
        return path;
    }

    private Path writeUnixScript(Path dir) throws IOException {
        Path path = dir.resolve(UNIX_SCRIPT_NAME);
        String content = "#!/bin/sh\n"
                + "case \"$1\" in\n"
                + "  *[Uu]sername*) printf '%s\\n' \"$AGENT_GIT_USERNAME\" ;;\n"
                + "  *) printf '%s\\n' \"$AGENT_GIT_PASSWORD\" ;;\n"
                + "esac\n";
        Files.write(path, content.getBytes(StandardCharsets.US_ASCII));
        makeExecutable(path);
        return path;
    }

    private void makeExecutable(Path path) {
        try {
            path.toFile().setExecutable(true, false);
        } catch (SecurityException e) {
            log.warn("git-askpass-chmod-failed path={} reason={}", path, e.getMessage());
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
