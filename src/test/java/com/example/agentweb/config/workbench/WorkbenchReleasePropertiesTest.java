package com.example.agentweb.config.workbench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workbench 发布开关的 fail-closed 配置测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchReleasePropertiesTest {

    @Test
    void defaultsShouldKeepEveryWorkbenchEntryClosed() {
        WorkbenchReleaseProperties properties =
                new WorkbenchReleaseProperties();

        assertFalse(properties.isEnabled());
        assertFalse(properties.isCreateEnabled());
        assertFalse(properties.isWriteRunEnabled());
        properties.validate();
    }

    @Test
    void subordinateSwitchShouldNotBeArmedBehindDisabledMasterSwitch() {
        WorkbenchReleaseProperties properties =
                new WorkbenchReleaseProperties();
        properties.setCreateEnabled(true);

        assertThrows(IllegalStateException.class, properties::validate);

        properties.setEnabled(true);
        properties.validate();
        assertTrue(properties.isEnabled());
    }
}
