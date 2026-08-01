package com.example.agentweb.infra.runtime;

import java.util.List;

/**
 * 受控 Runtime 版本探测命令执行边界。
 *
 * @author alex
 * @since 2026-08-01
 */
interface RuntimeVersionProbeRunner {

    RuntimeVersionProbeResult run(List<String> command);
}
