package com.example.agentweb.app.workbench.handoff;

import com.example.agentweb.domain.workbench.WorkbenchRunReference;

import java.util.List;

/**
 * 将调用方提交的 Run ID 解析为经过核验的安全领域引用。
 *
 * <p>实现方必须为每个 ID 返回完整引用或整体失败，不得返回缺失元数据的半截引用；
 * Workbench 归属与重复引用仍由 PhaseHandoff 聚合最终校验。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public interface HandoffRunReferenceResolver {

    List<WorkbenchRunReference> requireReferences(List<String> runIds);
}
