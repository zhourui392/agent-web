package com.example.agentweb.infra.issuelog;

import com.example.agentweb.domain.issuelog.IndexMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IndexMetadataExtractor} 单测,覆盖 INDEX.md 不存在 / 真实 22 行样本 /
 * 复合类型拆分 / 含空格服务拆分 / 重复去重保序 等场景。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
public class IndexMetadataExtractorTest {

    private final IndexMetadataExtractor extractor = new IndexMetadataExtractor();

    @Test
    public void file_not_exist_should_return_empty_metadata(@TempDir Path tmp) {
        IndexMetadata meta = extractor.extract(tmp.resolve("INDEX.md"));

        assertTrue(meta.getCategories().isEmpty());
        assertTrue(meta.getServices().isEmpty());
    }

    @Test
    public void empty_header_no_data_rows_should_return_empty_metadata(@TempDir Path tmp) throws IOException {
        Path index = writeIndex(tmp, ""
                + "# Issue Log Index\n"
                + "\n"
                + "| ID | 标题 | 类型 | 服务 | 文件 |\n"
                + "|----|------|------|------|------|\n");

        IndexMetadata meta = extractor.extract(index);

        assertTrue(meta.getCategories().isEmpty());
        assertTrue(meta.getServices().isEmpty());
    }

    @Test
    public void single_row_single_category_single_service_should_parse_correctly(@TempDir Path tmp) throws IOException {
        Path index = writeIndex(tmp, ""
                + "| ID | 标题 | 类型 | 服务 | 文件 |\n"
                + "|----|------|------|------|------|\n"
                + "| I-001 | FCM Token Redis Key 不含服务前缀 | key-format | notification-service | [I-001](issue/I-001.md) |\n");

        IndexMetadata meta = extractor.extract(index);

        assertEquals(Arrays.asList("key-format"), meta.getCategories());
        assertEquals(Arrays.asList("notification-service"), meta.getServices());
    }

    @Test
    public void composite_category_split_by_slash_should_split_into_independent_categories(@TempDir Path tmp) throws IOException {
        Path index = writeIndex(tmp, ""
                + "| ID | 标题 | 类型 | 服务 | 文件 |\n"
                + "|----|------|------|------|------|\n"
                + "| I-014 | 印尼号验证码 | env-diff/config-trap/logic-pitfall | user-service | [I-014](issue/I-014.md) |\n");

        IndexMetadata meta = extractor.extract(index);

        assertEquals(3, meta.getCategories().size());
        assertTrue(meta.getCategories().contains("env-diff"));
        assertTrue(meta.getCategories().contains("config-trap"));
        assertTrue(meta.getCategories().contains("logic-pitfall"));
    }

    @Test
    public void service_column_with_slash_space_separator_should_trim_and_split(@TempDir Path tmp) throws IOException {
        Path index = writeIndex(tmp, ""
                + "| ID | 标题 | 类型 | 服务 | 文件 |\n"
                + "|----|------|------|------|------|\n"
                + "| I-019 | bill number | logic-pitfall | payment-channel / payment-service | [I-019](issue/I-019.md) |\n");

        IndexMetadata meta = extractor.extract(index);

        assertEquals(2, meta.getServices().size());
        assertTrue(meta.getServices().contains("payment-channel"));
        assertTrue(meta.getServices().contains("payment-service"));
        // trim 不留前后空格
        assertFalse(meta.getServices().stream().anyMatch(s -> s.startsWith(" ") || s.endsWith(" ")));
    }

    @Test
    public void multiple_rows_duplicate_values_should_deduplicate_keeping_first_occurrence_order(@TempDir Path tmp) throws IOException {
        Path index = writeIndex(tmp, ""
                + "| ID | 标题 | 类型 | 服务 | 文件 |\n"
                + "|----|------|------|------|------|\n"
                + "| I-001 | x | key-format | service-a | [.](.) |\n"
                + "| I-002 | y | tool-missing | service-b | [.](.) |\n"
                + "| I-003 | z | key-format | service-a | [.](.) |\n");

        IndexMetadata meta = extractor.extract(index);

        assertEquals(Arrays.asList("key-format", "tool-missing"), meta.getCategories());
        assertEquals(Arrays.asList("service-a", "service-b"), meta.getServices());
    }

    @Test
    public void real_22_row_sample_should_correctly_extract_known_categories_and_services(@TempDir Path tmp) throws IOException {
        Path index = writeIndex(tmp, realSampleIndex());

        IndexMetadata meta = extractor.extract(index);

        // 真实样本里出现过的类型
        assertTrue(meta.getCategories().contains("key-format"));
        assertTrue(meta.getCategories().contains("tool-missing"));
        assertTrue(meta.getCategories().contains("config-trap"));
        assertTrue(meta.getCategories().contains("logic-pitfall"));
        assertTrue(meta.getCategories().contains("env-diff"));
        assertTrue(meta.getCategories().contains("field-mapping"));

        // 真实样本里出现过的服务
        assertTrue(meta.getServices().contains("notification-service"));
        assertTrue(meta.getServices().contains("inventory-service"));
        assertTrue(meta.getServices().contains("payment-service"));
        assertTrue(meta.getServices().contains("payment-channel"));
        assertTrue(meta.getServices().contains("risk-service"));
        // "公共工具" 作为有效 token 收录
        assertTrue(meta.getServices().contains("公共工具"));
    }

    @Test
    public void missing_header_separator_should_ignore_file_and_return_empty(@TempDir Path tmp) throws IOException {
        // 没有 |----| 分隔行,任何 | 开头的行都不算数据行
        Path index = writeIndex(tmp, ""
                + "# Issue Log Index\n"
                + "| I-001 | 标题 | key-format | service-x | [.](.) |\n");

        IndexMetadata meta = extractor.extract(index);

        assertTrue(meta.getCategories().isEmpty(), "无分隔符时不应把伪装的标题行当数据");
        assertTrue(meta.getServices().isEmpty());
    }

    private Path writeIndex(Path tmp, String content) throws IOException {
        Path file = tmp.resolve("INDEX.md");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private String realSampleIndex() {
        return ""
                + "# Issue Log Index — Project 项目已知陷阱\n"
                + "\n"
                + "**排查前先查阅本索引,避免重复踩坑。**\n"
                + "\n"
                + "| ID | 标题 | 类型 | 服务 | 文件 |\n"
                + "|----|------|------|------|------|\n"
                + "| I-001 | FCM Token Redis Key 不含服务前缀 | key-format | notification-service | [I-001](issue/I-001.md) |\n"
                + "| I-002 | redis-reader scan cluster | tool-missing | 公共工具 | [I-002](issue/I-002.md) |\n"
                + "| I-003 | 按服务名过滤日志可能遗漏 | tool-missing | 公共工具 | [I-003](issue/I-003.md) |\n"
                + "| I-004 | ES 告警归属不一致 | logic-pitfall | order-service | [I-004](issue/I-004.md) |\n"
                + "| I-005 | stock-center 幂等冲突误告警 | logic-pitfall | inventory-service | [I-005](issue/I-005.md) |\n"
                + "| I-006 | 日志查询 prod 环境差异 | env-diff | 公共工具 | [I-006](issue/I-006.md) |\n"
                + "| I-013 | Mock 支付接口职责混淆 | logic-pitfall | payment-service | [I-013](issue/I-013.md) |\n"
                + "| I-014 | 印尼号验证码走 WhatsApp | env-diff/config-trap/logic-pitfall | user-service | [I-014](issue/I-014.md) |\n"
                + "| I-018 | 风控按名单顺序查 | logic-pitfall | risk-service | [I-018](issue/I-018.md) |\n"
                + "| I-019 | bill number 字段映射 | logic-pitfall/field-mapping | payment-channel / payment-service | [I-019](issue/I-019.md) |\n";
    }
}
