package com.example.agentweb.app.auth;

import com.example.agentweb.domain.auth.UserAccount;
import com.example.agentweb.domain.auth.UserRegistrationService;
import com.example.agentweb.domain.auth.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 管理后台用户应用服务，仅负责编排注册用例和读模型查询。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Service
public class AdminUserAppService {

    private final UserRegistrationService registrationService;
    private final UserAccountQueryService queryService;

    public AdminUserAppService(UserRegistrationService registrationService,
                               UserAccountQueryService queryService) {
        this.registrationService = registrationService;
        this.queryService = queryService;
    }

    @Transactional(readOnly = true)
    public List<AdminUserView> listUsers() {
        return queryService.listAll();
    }

    @Transactional
    public AdminUserView createUser(String username, String rawPassword, UserRole role) {
        UserAccount account = registrationService.register(username, rawPassword, role);
        return AdminUserView.from(account);
    }
}
