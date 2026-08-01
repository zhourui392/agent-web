package com.example.agentweb.app.workbench;

import com.example.agentweb.app.workbench.port.WorkspaceInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkspaceInspectionAppService} 的纯编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceInspectionAppServiceTest {

    @Mock
    private WorkspaceInspector workspaceInspector;

    @InjectMocks
    private WorkspaceInspectionAppService service;

    @Test
    void inspectShouldDelegateWorkspaceRootAndReturnPortResult() {
        WorkspaceInspection expected = new WorkspaceInspection(
                "/home/ubuntu/workspace", "inspection-1",
                WorkspaceInspectionSource.DISCOVERY,
                Collections.emptyList(), Collections.emptyList());
        when(workspaceInspector.inspect("/home/ubuntu/workspace")).thenReturn(expected);

        WorkspaceInspection actual = service.inspect("/home/ubuntu/workspace");

        assertSame(expected, actual);
        verify(workspaceInspector).inspect("/home/ubuntu/workspace");
    }
}
