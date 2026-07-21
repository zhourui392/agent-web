package com.example.agentweb.infra.workspace;

import com.example.agentweb.adapter.workspace.WorkspaceProvisioner;
import com.example.agentweb.app.requirement.RequirementProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 工作区 infra 装配：仓储/租约（单 bean 双接口）与 Git provisioner，随需求线总开关启停。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Configuration
@ConditionalOnProperty(name = "agent.requirement.enabled", havingValue = "true")
public class WorkspaceInfraConfig {

    @Bean
    public SqliteWorkspaceRepo sqliteWorkspaceRepo(JdbcTemplate jdbc, RequirementProperties properties) {
        return new SqliteWorkspaceRepo(jdbc,
                properties.getWorkspace().getPortRangeStart(),
                properties.getWorkspace().getPortRangeEnd());
    }

    @Bean
    public WorkspaceProvisioner workspaceProvisioner(RequirementProperties properties) {
        return new GitWorktreeProvisioner(properties.getWorkspace().getRoot());
    }
}
