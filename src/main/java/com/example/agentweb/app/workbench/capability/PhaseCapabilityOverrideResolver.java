package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfile;

import java.util.List;

/**
 * 依据可信 Profile 和公共 Catalog 解析原始 Override ID 的应用端口。
 *
 * <p>实现方负责保留 fail-closed：拒绝重复、错误 required/optional 分类、未知或不可信 ID，
 * 并通过 CapabilityOverride 工厂保留互斥规则。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public interface PhaseCapabilityOverrideResolver {

    CapabilityOverride resolve(
            PhaseCapabilityProfile profile,
            CapabilityOverrideSelection selection);

    CapabilityOverride resolveSelected(
            PhaseCapabilityProfile profile,
            List<String> optionalSkillIds,
            List<String> optionalMcpServerIds,
            String additionalRule);
}
