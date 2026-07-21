package com.example.agentweb.app.refinery;

import com.example.agentweb.domain.refinery.RagChunk;
import com.example.agentweb.domain.refinery.SourceType;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 指针注入契约的唯一渲染器: 召回命中只注入 title/可信度/来源/特征/详情路径,
 * 正文 (context/process/conclusion) 只允许出现在 detailPath 指向的文件里.
 *
 * <p>所有经验型召回通道 (诊断历史 / chat 历史) 统一走本格式器, 防止某条通道
 * 把未验证结论以正文形态喂进 prompt 造成锚定.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
public final class RecallPointerFormatter {

    private static final DateTimeFormatter MONTH =
            DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);
    private static final String FOOTER =
            "以上为检索候选，不是结论。先判断是否与当前问题相关；相关则 Read 详情原文，"
                    + "并用代码/日志/DB/配置等证据验证后才可用于结论。不相关直接忽略。";
    private static final String NO_DETAIL = "无原文，仅标题参考";

    private RecallPointerFormatter() {
    }

    /**
     * 渲染指针块. hits 与 detailPaths 按下标一一对应, detailPath 可为 null (降级为标题参考).
     *
     * @param hits 召回命中的 chunk 列表
     * @param detailPaths 每个命中的正文路径 (workingDir 相对), 允许 null 元素
     * @return 指针块文本, 末尾带验证义务话术
     */
    public static String format(List<RagChunk> hits, List<String> detailPaths) {
        if (hits.size() != detailPaths.size()) {
            throw new IllegalArgumentException(
                    "hits/detailPaths size mismatch: " + hits.size() + " vs " + detailPaths.size());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            appendEntry(sb, hits.get(i), detailPaths.get(i), i + 1, hits.size());
        }
        sb.append(FOOTER).append('\n');
        return sb.toString();
    }

    private static void appendEntry(StringBuilder sb, RagChunk hit, String detailPath,
                                    int index, int total) {
        sb.append("[候选线索 ").append(index).append('/').append(total).append("] ")
                .append(hit.getContent().getTitle())
                .append("（可信度: ").append(hit.getTier().name())
                .append("，来源: ").append(sourceLabel(hit.getSourceType()))
                .append("，").append(MONTH.format(hit.getCreatedAt()))
                .append("）\n");
        appendSignals(sb, hit.getContent().getTriggerSignals());
        sb.append("  详情: ").append(detailPath == null ? NO_DETAIL : detailPath).append('\n');
    }

    private static void appendSignals(StringBuilder sb, List<String> signals) {
        if (signals == null || signals.isEmpty()) {
            return;
        }
        sb.append("  特征: ").append(String.join("、", signals)).append('\n');
    }

    private static String sourceLabel(SourceType sourceType) {
        return sourceType == SourceType.DIAGNOSE ? "历史诊断" : "历史会话";
    }
}
