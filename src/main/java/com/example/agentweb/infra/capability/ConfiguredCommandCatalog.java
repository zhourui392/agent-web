package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.CapabilitySourceConfigurationRepository;
import com.example.agentweb.domain.capability.CommandCatalog;
import com.example.agentweb.domain.capability.CommandDefinition;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Collections;
import java.util.List;

/**
 * 使用数据库 Capability Source Configuration 的 Workbench Command Catalog。
 *
 * @author alex
 * @since 2026-08-05
 */
@Component
public class ConfiguredCommandCatalog implements CommandCatalog {

    private final CapabilitySourceConfigurationRepository repository;
    private final Clock clock;

    public ConfiguredCommandCatalog(
            CapabilitySourceConfigurationRepository repository, Clock clock) {
        if (repository == null || clock == null) {
            throw new IllegalArgumentException(
                    "configured command catalog dependencies are required");
        }
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public List<CommandDefinition> discover() {
        return repository.find()
                .map(configuration -> new FileSystemCommandCatalog(
                        configuration.getCommandCatalogDirectories(), clock).discover())
                .orElse(Collections.emptyList());
    }
}
