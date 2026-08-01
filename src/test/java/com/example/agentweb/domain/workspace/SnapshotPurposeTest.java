package com.example.agentweb.domain.workspace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 公共快照目的值对象不泄漏任何消费者枚举。

 * @author alex
 * @since 2026-08-01
 */
class SnapshotPurposeTest {

    @Test
    void acceptsStableConsumerQualifiedPurpose() {
        SnapshotPurpose purpose = SnapshotPurpose.of("WORKBENCH_RUN_START");

        assertEquals("WORKBENCH_RUN_START", purpose.getValue());
        assertEquals(purpose, SnapshotPurpose.of("WORKBENCH_RUN_START"));
        assertNotEquals(purpose, SnapshotPurpose.of("WORKBENCH_RUN_END"));
    }

    @Test
    void rejectsBlankLowercaseAndOversizedPurpose() {
        assertThrows(IllegalArgumentException.class, () -> SnapshotPurpose.of(" "));
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotPurpose.of("workbench_create"));
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotPurpose.of(repeat('A', 65)));
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
