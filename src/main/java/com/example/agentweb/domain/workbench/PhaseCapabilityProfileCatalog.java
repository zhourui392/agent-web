package com.example.agentweb.domain.workbench;

/**
 * Workbench 四阶段默认 Capability Profile 的可信 Catalog 领域端口。
 *
 * <p>缺失或损坏时必须明确失败，禁止实现方在运行期临时猜测默认值。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public interface PhaseCapabilityProfileCatalog {

    PhaseCapabilityProfile requireProfile(WorkbenchPhase phase);
}
