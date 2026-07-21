package com.example.agentweb.domain.refinery;

/**
 * refinery chunk 的过期分类. 不同类别对应不同的默认 TTL 天数, 由 RagChunkFactory 转换为
 * 具体的 expires_at 时间戳 (见 design doc § 6.3).
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public enum TtlCategory {

    /** 具体到 file/line/symbol/类名/方法名的代码细节, 代码改动后易失效. */
    CODE,

    /** 部署 / 配置 / 环境变量 / CI/CD / 容器编排相关. */
    DEPLOY,

    /** 业务流程 / 数据关系 / 表关系 / 领域规则. */
    BUSINESS,

    /** 通用排障手法 / 工具使用技巧 / 跨场景工程经验, 不易过期. */
    GENERAL;

    /**
     * 从 LLM 输出的字符串解析为枚举, 大小写不敏感.
     *
     * @param raw LLM 输出的 ttl_category 字段
     * @return 对应枚举
     * @throws IllegalArgumentException 无法解析
     */
    public static TtlCategory fromString(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("ttl_category required");
        }
        return TtlCategory.valueOf(raw.trim().toUpperCase());
    }
}
