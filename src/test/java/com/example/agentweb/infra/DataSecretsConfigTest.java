package com.example.agentweb.infra;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产配置中的本地敏感配置导入契约。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class DataSecretsConfigTest {

    @Test
    void applicationConfig_shouldImportGitIgnoredDataSecrets() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application",
                new FileSystemResource("src/main/resources/application.yml"));

        Object imports = sources.get(0).getProperty("spring.config.import");

        assertNotNull(imports);
        assertTrue(imports.toString().contains("classpath:agent-paths.yml"));
        assertFalse(imports.toString().contains("optional:classpath:agent-paths.yml"));
        assertTrue(imports.toString().contains("optional:file:./data/secrets.properties"));
    }
}
