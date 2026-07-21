package com.example.agentweb.infra.issuelog;

import com.example.agentweb.domain.issuelog.IndexMetadata;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 从 INDEX.md 抽取已存在的类型与服务清单。
 *
 * <p>解析约定:</p>
 * <ul>
 *   <li>必须存在表头分隔符行(如 {@code |----|------|...|}),其后才视为数据行</li>
 *   <li>数据行用 {@code |} 拆分,取第 3 列(类型)和第 4 列(服务)</li>
 *   <li>类型与服务均按 {@code /} 拆分子段,每段 trim 后非空才收录</li>
 *   <li>结果保持首次出现顺序去重</li>
 * </ul>
 *
 * <p>文件不存在或解析异常时返回 {@link IndexMetadata#EMPTY},不抛异常,
 * 由调用方根据空集决定降级行为。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
@Component
public class IndexMetadataExtractor {

    /** 表头分隔行:必须只含 |、空白、连字符、冒号(对齐符),且至少一段连续连字符。 */
    private static final Pattern SEPARATOR_LINE = Pattern.compile("^\\s*\\|[\\s\\-:|]+\\|\\s*$");
    private static final Pattern HAS_DASH = Pattern.compile("-{3,}");

    /** 类型列在 split 后的索引(空首段 + ID + 标题 + 类型 = 索引 3)。 */
    private static final int CATEGORY_COLUMN_INDEX = 3;
    private static final int SERVICE_COLUMN_INDEX = 4;

    public IndexMetadata extract(Path indexPath) {
        if (indexPath == null || !Files.exists(indexPath)) {
            return IndexMetadata.EMPTY;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(indexPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return IndexMetadata.EMPTY;
        }

        int dataStartIndex = locateDataStart(lines);
        if (dataStartIndex < 0) {
            return IndexMetadata.EMPTY;
        }

        LinkedHashSet<String> categories = new LinkedHashSet<>();
        LinkedHashSet<String> services = new LinkedHashSet<>();
        for (int i = dataStartIndex; i < lines.size(); i++) {
            collectFromRow(lines.get(i), categories, services);
        }

        return new IndexMetadata(new ArrayList<>(categories), new ArrayList<>(services));
    }

    /** 返回首条数据行的下标;未找到表头分隔符返回 -1。 */
    private int locateDataStart(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (SEPARATOR_LINE.matcher(line).matches() && HAS_DASH.matcher(line).find()) {
                return i + 1;
            }
        }
        return -1;
    }

    private void collectFromRow(String row,
                                LinkedHashSet<String> categories,
                                LinkedHashSet<String> services) {
        if (row == null || !row.trim().startsWith("|")) {
            return;
        }
        String[] cols = row.split("\\|", -1);
        addTokens(extractColumn(cols, CATEGORY_COLUMN_INDEX), categories);
        addTokens(extractColumn(cols, SERVICE_COLUMN_INDEX), services);
    }

    private String extractColumn(String[] cols, int index) {
        if (index >= cols.length) {
            return "";
        }
        return cols[index].trim();
    }

    private void addTokens(String cellValue, LinkedHashSet<String> sink) {
        if (cellValue.isEmpty()) {
            return;
        }
        for (String token : cellValue.split("/")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                sink.add(trimmed);
            }
        }
    }
}
