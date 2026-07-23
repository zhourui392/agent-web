package com.example.agentweb.app.setting;

import com.example.agentweb.app.setting.port.WorkspaceSettingsSeedProvider;
import com.example.agentweb.domain.setting.WorkspaceSettings;
import com.example.agentweb.domain.setting.WorkspaceSettingsPolicy;
import com.example.agentweb.domain.setting.WorkspaceSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作空间设置应用服务测试：只验证查询回退和写入编排。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class WorkspaceSettingsAppServiceTest {

    private WorkspaceSettingsRepository repository;
    private WorkspaceSettingsPolicy policy;
    private WorkspaceSettingsSeedProvider seedProvider;
    private WorkspaceSettingsAppServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkspaceSettingsRepository.class);
        policy = mock(WorkspaceSettingsPolicy.class);
        seedProvider = mock(WorkspaceSettingsSeedProvider.class);
        service = new WorkspaceSettingsAppServiceImpl(repository, policy, seedProvider);
    }

    @Test
    void get_databaseConfigured_shouldReturnDatabaseValue() {
        WorkspaceSettings stored = settings("/srv/stored");
        when(repository.find()).thenReturn(Optional.of(stored));

        assertSame(stored, service.get());
    }

    @Test
    void get_databaseMissing_shouldReturnConfigurationSeed() {
        WorkspaceSettings seed = settings("/srv/seed");
        when(repository.find()).thenReturn(Optional.empty());
        when(seedProvider.get()).thenReturn(seed);

        assertSame(seed, service.get());
    }

    @Test
    void update_shouldValidateBeforeSaving() {
        WorkspaceSettings updated = settings("/srv/updated");

        service.update(updated);

        InOrder order = inOrder(policy, repository);
        order.verify(policy).requireUsable(updated);
        order.verify(repository).save(updated);
    }

    @Test
    void reset_shouldDeleteDatabaseConfiguration() {
        service.reset();

        verify(repository).delete();
    }

    private WorkspaceSettings settings(String root) {
        return WorkspaceSettings.create(root, Collections.singletonList(root),
                Collections.<String>emptyList());
    }
}
