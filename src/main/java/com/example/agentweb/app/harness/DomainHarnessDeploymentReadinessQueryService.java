package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通过 HarnessRun 聚合查询 DEPLOYMENT 领域准备状态。
 *
 * @author alex
 * @since 2026-07-24
 */
@Service
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class DomainHarnessDeploymentReadinessQueryService
        implements HarnessDeploymentReadinessQueryService {

    private final HarnessRunRepository runRepository;

    public DomainHarnessDeploymentReadinessQueryService(HarnessRunRepository runRepository) {
        this.runRepository = runRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public HarnessDeploymentReadinessView find(String runId) {
        HarnessRun run = runRepository.findById(runId)
                .orElseThrow(() -> new HarnessRunNotFoundException(runId));
        return new HarnessDeploymentReadinessView(run.deploymentReadiness());
    }
}
