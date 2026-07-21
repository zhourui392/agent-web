package com.example.agentweb.domain.shared;

/**
 * 业务通用 verdict 词汇表（平台无关）：业务方对一次诊断结论的人工裁决。
 *
 * <p>IM 反馈卡片按钮（写侧源头）、issue-log 回填分类、refinery tier 升降、链路质量指标
 * 均以本枚举为唯一出处，禁止各处再复刻字符串常量。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
public enum Verdict {

    /** 结论正确（正反馈）。 */
    CORRECT("结论正确"),

    /** 有帮助（正反馈）。 */
    HELPFUL("有帮助"),

    /** 结论错误（负反馈）。 */
    WRONG("结论错误"),

    /** 未标注 / 未知取值（不可当负反馈处理）。 */
    UNSPECIFIED(null);

    private final String raw;

    Verdict(String raw) {
        this.raw = raw;
    }

    /**
     * 按原始字面值解析；null / 空白 / 未知取值一律 {@link #UNSPECIFIED}，
     * 绝不抛异常——上游是自由文本字段，未知值不能中断主流程。
     *
     * @param raw 原始 verdict 字面值
     * @return 对应枚举，无匹配时 UNSPECIFIED
     */
    public static Verdict fromRaw(String raw) {
        if (raw == null) {
            return UNSPECIFIED;
        }
        String trimmed = raw.trim();
        for (Verdict verdict : values()) {
            if (verdict.raw != null && verdict.raw.equals(trimmed)) {
                return verdict;
            }
        }
        return UNSPECIFIED;
    }

    /** 是否正反馈（结论正确 / 有帮助）。 */
    public boolean isPositive() {
        return this == CORRECT || this == HELPFUL;
    }

    /** 是否负反馈（结论错误）。 */
    public boolean isNegative() {
        return this == WRONG;
    }

    /**
     * 规范字面值（用于 SQL 参数、IM 卡片按钮等需要原始字符串的场合）。
     *
     * @return 规范字面值；UNSPECIFIED 为 null
     */
    public String raw() {
        return raw;
    }
}
