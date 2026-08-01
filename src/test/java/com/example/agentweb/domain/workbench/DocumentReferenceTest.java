package com.example.agentweb.domain.workbench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 文档引用的逻辑仓库 Key 与严格 POSIX 相对路径边界测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class DocumentReferenceTest {

    @Test
    void givenMultiSegmentLogicalRepositoryKeyWhenCreateThenRetainItWithoutPathResolution() {
        DocumentReference reference = DocumentReference.of(
                "service/api", "src/main/App.java");

        assertEquals("service/api", reference.getRepositoryKey());
        assertEquals("src/main/App.java", reference.getRelativePath());
    }

    @Test
    void givenAnyIsoControlInEitherValueWhenCreateThenRejectIt() {
        for (int codePoint = Character.MIN_VALUE;
             codePoint <= Character.MAX_VALUE; codePoint++) {
            if (!Character.isISOControl(codePoint)) {
                continue;
            }
            String control = String.valueOf((char) codePoint);
            assertThrows(IllegalArgumentException.class,
                    () -> DocumentReference.of(
                            "service" + control + "api", "src/App.java"));
            assertThrows(IllegalArgumentException.class,
                    () -> DocumentReference.of(
                            "service/api", "src/" + control + "App.java"));
        }
    }

    @Test
    void givenAbsoluteDriveOrBackslashSyntaxWhenCreateThenRejectIt() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("/service/api", "src/App.java")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("C:/service/api", "src/App.java")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("C:service/api", "src/App.java")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("service\\api", "src/App.java")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("service/api", "/etc/passwd")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("service/api", "C:/secret.txt")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("service/api", "C:secret.txt")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("service/api", "src\\App.java")));
    }

    @Test
    void givenEmptyDotOrParentSegmentWhenCreateThenRejectIt() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("service//api", "src/App.java")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("service/./api", "src/App.java")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("service/../api", "src/App.java")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("service/api/", "src/App.java")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("service/api", "src//App.java")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("service/api", "./src/App.java")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("service/api", "src/./App.java")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("service/api", "src/../App.java")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DocumentReference.of("service/api", "src/App.java/")));
    }
}
