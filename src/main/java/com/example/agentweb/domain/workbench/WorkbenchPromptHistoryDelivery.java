package com.example.agentweb.domain.workbench;

/**
 * Workbench 最终 Prompt 向 Runtime 投递历史的冻结方式。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum WorkbenchPromptHistoryDelivery {
    PROMPT_PREFIX,
    PROVIDER_RESUME,
    TYPED
}
