package com.example.agentweb.infra.runtime;

/**
 * Claude 方言 BuildContext 的模式下发附加字段。
 *
 * @author alex
 * @since 2026-08-20
 */
record ClaudeBuildContextExtras(
        String appendSystemPrompt,
        String permissionMode,
        String mcpConfigPath,
        String pluginDir) {

    static final ClaudeBuildContextExtras NONE =
            new ClaudeBuildContextExtras(null, null, null, null);
}
