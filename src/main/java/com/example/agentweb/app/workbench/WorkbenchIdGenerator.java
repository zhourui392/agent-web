package com.example.agentweb.app.workbench;

import com.example.agentweb.domain.workbench.WorkbenchId;

/**
 * Workbench 标识生成端口。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkbenchIdGenerator {

    WorkbenchId nextId();
}
