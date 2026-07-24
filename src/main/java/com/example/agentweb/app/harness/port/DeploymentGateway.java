package com.example.agentweb.app.harness.port;

import com.example.agentweb.domain.harness.DeploymentOutcome;

/**
 * 受控 local 部署命令执行端口。
 *
 * @author alex
 * @since 2026-07-23
 */
public interface DeploymentGateway {

    DeploymentOutcome execute(DeploymentExecutionSpec spec);
}
