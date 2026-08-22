package com.example.agentweb.app.workbench;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Workbench 写路径统一门禁：统一入口上线后实例写路径下线（只读保留历史）。
 *
 * <p>默认拒绝（405 语义由接口层映射）；应急可通过
 * {@code agent.workbench.write-enabled=true} 临时恢复。</p>
 *
 * @author alex
 * @since 2026-08-20
 */
@Component
public class WorkbenchWriteGate {

    /** 统一 Chat/Workbench 后 Workbench 写路径默认下线。 */
    public static final class WorkbenchReadOnlyException extends RuntimeException {
        public WorkbenchReadOnlyException() {
            super("workbench write paths are retired after the unified chat entry; "
                    + "history is read-only");
        }
    }

    private final boolean writeEnabled;

    public WorkbenchWriteGate(
            @Value("${agent.workbench.write-enabled:false}") boolean writeEnabled) {
        this.writeEnabled = writeEnabled;
    }

    public void requireWritable() {
        if (!writeEnabled) {
            throw new WorkbenchReadOnlyException();
        }
    }

    public boolean isWriteEnabled() {
        return writeEnabled;
    }
}
