package com.example.agentweb.app.auth;

import com.example.agentweb.domain.auth.UserAccount;
import com.example.agentweb.domain.auth.UserRegistrationService;
import com.example.agentweb.domain.auth.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminUserAppService} 管理员用户编排测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class AdminUserAppServiceTest {

    private UserRegistrationService registrationService;
    private UserAccountQueryService queryService;
    private AdminUserAppService service;

    @BeforeEach
    void setUp() {
        registrationService = mock(UserRegistrationService.class);
        queryService = mock(UserAccountQueryService.class);
        service = new AdminUserAppService(registrationService, queryService);
    }

    @Test
    void createUser_should_DelegateRegistrationAndReturnSafeView() {
        Instant now = Instant.parse("2026-07-22T08:00:00Z");
        UserAccount account = UserAccount.restore(
                "user-id", "zhangsan", "secret-hash", UserRole.USER, true, now, now);
        when(registrationService.register(
                "zhangsan", "A-secure-password!2026", UserRole.USER)).thenReturn(account);

        AdminUserView result = service.createUser(
                "zhangsan", "A-secure-password!2026", UserRole.USER);

        verify(registrationService).register(
                "zhangsan", "A-secure-password!2026", UserRole.USER);
        assertEquals("user-id", result.getId());
        assertEquals("zhangsan", result.getUsername());
        assertEquals(UserRole.USER, result.getRole());
        assertEquals(now, result.getCreatedAt());
    }

    @Test
    void listUsers_should_DelegateToReadSideQueryService() {
        List<AdminUserView> users = Arrays.asList(
                new AdminUserView("admin", "admin", UserRole.ADMIN, true,
                        Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z")));
        when(queryService.listAll()).thenReturn(users);

        assertEquals(users, service.listUsers());
        verify(queryService).listAll();
    }
}
