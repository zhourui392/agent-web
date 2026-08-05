package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.WorkbenchStageInstanceIdentifierGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * UUID 动态 Stage Instance 标识生成器。
 *
 * @author alex
 * @since 2026-08-05
 */
@Component
public final class UuidWorkbenchStageInstanceIdentifierGenerator
        implements WorkbenchStageInstanceIdentifierGenerator {

    @Override
    public String nextIdentifier() {
        return "stage-" + UUID.randomUUID();
    }
}
