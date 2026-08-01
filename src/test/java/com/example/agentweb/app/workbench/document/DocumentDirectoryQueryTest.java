package com.example.agentweb.app.workbench.document;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Document tree 查询的逻辑仓库、根目录和有界参数测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class DocumentDirectoryQueryTest {

    @Test
    void givenRepositoryRootWhenCreateThenRetainLogicalScope() {
        DocumentDirectoryQuery query = new DocumentDirectoryQuery(
                "service/api", "", 1000);

        assertEquals("service/api", query.getRepositoryKey());
        assertEquals("", query.getRelativePath());
        assertEquals(1000, query.getLimit());
    }

    @Test
    void givenUnsafeDirectoryPathWhenCreateThenRejectIt() {
        for (String path : Arrays.asList(
                "/etc", "C:/secret", "C:secret", "docs\\api",
                "docs//api", "./docs", "docs/./api", "docs/../api",
                "docs/", "docs\napi")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DocumentDirectoryQuery(
                            "service/api", path, 10), path);
        }
    }

    @Test
    void givenUnsafeRepositoryKeyOrLimitWhenCreateThenRejectIt() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DocumentDirectoryQuery("service\napi", "", 10)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DocumentDirectoryQuery("service/api", "", 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DocumentDirectoryQuery("service/api", "", 1001)));
    }
}
