package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 独立于 Workbench Owner 的真实管理员审计身份。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@EqualsAndHashCode
public final class WorkbenchAdministrator {

    private final String actorId;
    private final String actorName;

    private WorkbenchAdministrator(String actorId, String actorName) {
        this.actorId = DomainText.require(
                actorId, "workbench administrator actor id", 128);
        this.actorName = DomainText.require(
                actorName, "workbench administrator actor name", 256);
    }

    public static WorkbenchAdministrator fromAuthenticated(LoginUser user) {
        if (user == null || !user.isAdmin()) {
            throw new IllegalArgumentException(
                    "authenticated administrator is required");
        }
        return new WorkbenchAdministrator(
                user.getUserId(), user.getUserName());
    }
}
