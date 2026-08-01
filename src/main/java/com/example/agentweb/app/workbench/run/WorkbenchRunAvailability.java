package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.workbench.RunMode;

/**
 * Workbench Run 是否确定路由到公共 Runtime 的应用门禁。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkbenchRunAvailability {

    void requireAvailable(RunMode runMode);
}
