package com.example.agentweb.app.workbench.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Workbench Run 的安全运维日志边界，不接受外部异常 message 或 Throwable。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
class SafeWorkbenchRunLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            SafeWorkbenchRunLogger.class);

    void runtimeStopFailed(String runId, String failureType) {
        LOGGER.warn(
                "Workbench Run cancellation persisted but runtime stop failed, runId={}, failureType={}",
                runId, failureType);
    }
}
