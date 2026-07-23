package com.example.agentweb.infra;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CLI Agent 子进程环境变量隔离器。
 *
 * <p>仅保留进程运行所需的基础环境、代理/证书配置和 CLI 自身鉴权。飞书、embedding、
 * 数据库路径等服务端秘密不会被 Agent 及其派生命令继承。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
public class ProcessEnvironmentSanitizer {

    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            "PATH", "HOME", "USER", "LOGNAME", "SHELL", "TERM",
            "TMP", "TEMP", "TMPDIR", "LANG", "TZ",
            "SYSTEMROOT", "COMSPEC", "PATHEXT", "APPDATA", "LOCALAPPDATA", "USERPROFILE",
            "XDG_CONFIG_HOME", "XDG_CACHE_HOME",
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
            "SSL_CERT_FILE", "SSL_CERT_DIR", "NODE_EXTRA_CA_CERTS",
            "CODEX_HOME", "CLAUDE_CONFIG_DIR", "OPENAI_API_KEY", "ANTHROPIC_API_KEY"
    ));

    public void sanitize(ProcessBuilder processBuilder) {
        Iterator<Map.Entry<String, String>> iterator = processBuilder.environment().entrySet().iterator();
        while (iterator.hasNext()) {
            String name = iterator.next().getKey();
            String normalizedName = name.toUpperCase(Locale.ROOT);
            if (!ALLOWED.contains(normalizedName) && !normalizedName.startsWith("LC_")) {
                iterator.remove();
            }
        }
    }
}
