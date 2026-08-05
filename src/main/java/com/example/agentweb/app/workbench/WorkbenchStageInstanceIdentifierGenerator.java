package com.example.agentweb.app.workbench;

/**
 * 为 Workbench 内动态 Stage Instance 生成不透明稳定标识。
 *
 * @author alex
 * @since 2026-08-05
 */
public interface WorkbenchStageInstanceIdentifierGenerator {

    String nextIdentifier();
}
