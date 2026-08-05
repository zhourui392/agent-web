package com.example.agentweb.domain.capability;

import java.util.List;

/**
 * Workbench Command Definition 发现端口。
 *
 * @author alex
 * @since 2026-08-05
 */
public interface CommandCatalog {

    List<CommandDefinition> discover();
}
