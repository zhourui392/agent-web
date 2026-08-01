package com.example.agentweb.domain.capability;

import java.util.List;

/**
 * Skill 热发现端口，返回完整且已计算 Package Hash 的领域描述符。
 *
 * @author alex
 * @since 2026-07-23
 */
public interface SkillCatalog {

    List<SkillPackage> discover();
}
