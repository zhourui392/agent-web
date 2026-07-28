package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.HistoricalToolInvocationMigrator;
import com.example.agentweb.app.chatrun.ToolInvocationMigrationReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.migration.tool-invocations.enabled", havingValue = "true")
@Slf4j
public class ToolInvocationMigrationRunner implements ApplicationRunner {

    private final HistoricalToolInvocationMigrator migrator;
    private final boolean dryRun;
    private final int batchSize;

    public ToolInvocationMigrationRunner(HistoricalToolInvocationMigrator migrator,
            @Value("${app.migration.tool-invocations.dry-run:true}") boolean dryRun,
            @Value("${app.migration.tool-invocations.batch-size:250}") int batchSize) {
        this.migrator = migrator;
        this.dryRun = dryRun;
        this.batchSize = batchSize;
    }

    @Override
    public void run(ApplicationArguments args) {
        ToolInvocationMigrationReport report = migrator.migrate(dryRun, batchSize);
        log.info("tool-invocation-migration-finished dryRun={} report={}", dryRun, report);
    }
}
