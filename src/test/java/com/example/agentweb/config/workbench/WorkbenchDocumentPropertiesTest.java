package com.example.agentweb.config.workbench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workbench Document 技术限额配置测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchDocumentPropertiesTest {

    @Test
    void defaultsShouldBePositiveBoundedAndInternallyConsistent() {
        WorkbenchDocumentProperties properties = new WorkbenchDocumentProperties();

        properties.validate();

        assertAll(
                () -> assertEquals(1000, properties.getMaxDirectoryEntries()),
                () -> assertEquals(2L * 1024L * 1024L,
                        properties.getMaxTextBytes()),
                () -> assertEquals(2L * 1024L * 1024L,
                        properties.getMaxLogPreviewBytes()),
                () -> assertEquals(10L * 1024L * 1024L,
                        properties.getMaxImageBytes()),
                () -> assertEquals(50L * 1024L * 1024L,
                        properties.getMaxDownloadBytes()),
                () -> assertTrue(properties.getMaxTextBytes()
                        <= properties.getMaxDownloadBytes()),
                () -> assertTrue(properties.getMaxImageBytes()
                        <= properties.getMaxDownloadBytes()));
    }

    @Test
    void invalidLimitsShouldFailFast() {
        WorkbenchDocumentProperties properties = new WorkbenchDocumentProperties();
        properties.setMaxDirectoryEntries(1001);
        assertThrows(IllegalStateException.class, properties::validate);

        properties = new WorkbenchDocumentProperties();
        properties.setMaxTextBytes(0L);
        assertThrows(IllegalStateException.class, properties::validate);

        properties = new WorkbenchDocumentProperties();
        properties.setMaxDownloadBytes((long) Integer.MAX_VALUE + 1L);
        assertThrows(IllegalStateException.class, properties::validate);

        properties = new WorkbenchDocumentProperties();
        properties.setMaxImageBytes(properties.getMaxDownloadBytes() + 1L);
        assertThrows(IllegalStateException.class, properties::validate);
    }
}
