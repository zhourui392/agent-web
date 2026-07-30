package com.example.agentweb.config.nativeagent;

import com.anthropic.agentkit.infrastructure.config.LlmProvider;
import com.example.agentweb.config.EnvProperties;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Host-owned configuration for the in-process diagnosis runtime.
 *
 * @author alex
 * @since 2026-07-29
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "agent.native")
public class NativeDiagnosisProperties {

    private boolean enabled;
    private String boundEnvironment = "test";
    private LlmProvider provider = LlmProvider.OPENAI;
    private String model = "";
    private String apiKey = "";
    private String baseUrl = "";
    private String timezone = "UTC";
    private int maxTokens = 8192;
    private long timeoutSeconds = 1800L;
    private String promptPacks = "";
    private String skillsRoot = "";
    private Budget budget = new Budget();
    private Tools tools = new Tools();
    private Backends backends = new Backends();
    private LocalLogs localLogs = new LocalLogs();
    private Map<String, NativeDiagnosisProperties> environments =
            new LinkedHashMap<String, NativeDiagnosisProperties>();

    public void validate(EnvProperties environments) {
        if (!enabled) {
            return;
        }
        environmentConfigurations().forEach((environment, configuration) ->
                validateEnvironment(environment, configuration, environments));
    }

    public Map<String, NativeDiagnosisProperties> environmentConfigurations() {
        if (environments == null || environments.isEmpty()) {
            requireText(boundEnvironment, "agent.native.bound-environment");
            return Collections.singletonMap(boundEnvironment.trim(), this);
        }
        LinkedHashMap<String, NativeDiagnosisProperties> result = new LinkedHashMap<>();
        environments.forEach((name, configuration) -> {
            requireText(name, "agent.native.environments key");
            if (configuration == null) {
                throw invalid("environment configuration must not be null");
            }
            result.put(name.trim(), configuration);
        });
        if (result.size() != environments.size()) {
            throw invalid("environment names must be unique after trimming");
        }
        return Collections.unmodifiableMap(result);
    }

    private void validateEnvironment(String environment,
                                     NativeDiagnosisProperties configuration,
                                     EnvProperties publicEnvironments) {
        if (configuration.environments != null && !configuration.environments.isEmpty()) {
            throw invalid("nested NATIVE environment groups are not allowed");
        }
        configuration.requireText(configuration.model,
                "agent.native environment model");
        configuration.requireText(configuration.apiKey,
                "agent.native environment api-key");
        if (publicEnvironments.findByKey(environment) == null) {
            throw invalid("bound environment is not declared in agent.envs");
        }
        configuration.requirePositive(configuration.maxTokens, "max-tokens");
        configuration.requirePositive(configuration.timeoutSeconds, "timeout-seconds");
        configuration.zoneId();
        configuration.budget.validate();
        configuration.tools.validate();
        configuration.backends.validate();
        configuration.validateBackendPolicies();
        configuration.localLogs.validate();
        configuration.validateLogQuerySourceConflict();
        configuration.requireDirectoryIfConfigured(configuration.promptPacks, "prompt-packs");
        configuration.requireDirectoryIfConfigured(configuration.skillsRoot, "skills-root");
    }

    private void validateLogQuerySourceConflict() {
        if (localLogs.enabled && hasText(backends.logQueryUrl)) {
            throw invalid("local and remote LogQuery backends cannot both be enabled");
        }
    }

    private void validateBackendPolicies() {
        if (hasText(backends.esBaseUrl) && tools.empty(tools.allowedEsIndices)) {
            throw invalid("ES backend requires a non-empty index allowlist");
        }
        if (hasText(backends.mysqlJdbcUrl) && tools.empty(tools.allowedMysqlSchemas)) {
            throw invalid("MySQL backend requires a non-empty schema allowlist");
        }
        if (hasText(backends.redisHost) && tools.empty(tools.allowedRedisKeyPrefixes)) {
            throw invalid("Redis backend requires a non-empty key-prefix allowlist");
        }
    }

    private void requireDirectoryIfConfigured(String value, String name) {
        if (!hasText(value)) {
            return;
        }
        Path path = Path.of(value.trim());
        if (!Files.isDirectory(path) || !Files.isReadable(path)) {
            throw invalid(name + " must be a readable directory");
        }
    }

    private void requireText(String value, String name) {
        if (!hasText(value)) {
            throw invalid(name + " must not be blank");
        }
    }

    private void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw invalid(name + " must be positive");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private IllegalStateException invalid(String message) {
        return new IllegalStateException("Invalid NATIVE diagnosis configuration: " + message);
    }

    public ZoneId zoneId() {
        try {
            return ZoneId.of(timezone == null ? "" : timezone.trim());
        } catch (ZoneRulesException | IllegalArgumentException failure) {
            throw invalid("timezone must be a valid ZoneId");
        }
    }

    @Getter
    @Setter
    public class Budget {
        private int maxTurns = 20;
        private int maxToolCalls = 50;
        private long maxInputTokens = 400000L;
        private long maxOutputTokens = 50000L;
        private long maxOutputCharacters = 500000L;
        private int maxLlmCalls = 30;

        void validate() {
            requirePositive(maxTurns, "budget.max-turns");
            requirePositive(maxToolCalls, "budget.max-tool-calls");
            requirePositive(maxInputTokens, "budget.max-input-tokens");
            requirePositive(maxOutputTokens, "budget.max-output-tokens");
            requirePositive(maxOutputCharacters, "budget.max-output-characters");
            requirePositive(maxLlmCalls, "budget.max-llm-calls");
        }
    }

    @Getter
    @Setter
    public class Tools {
        private boolean httpEnabled;
        private Set<String> allowedHttpHosts = Collections.emptySet();
        private boolean dubboEnabled;
        private Set<String> allowedDubboAddresses = Collections.emptySet();
        private Set<String> allowedDubboMethods = Collections.emptySet();
        private Set<String> allowedEsIndices = Collections.emptySet();
        private Set<String> allowedMysqlSchemas = Collections.emptySet();
        private Set<String> allowedRedisKeyPrefixes = Collections.emptySet();
        private int maxCallsPerMinute = 60;

        void validate() {
            requirePositive(maxCallsPerMinute, "tools.max-calls-per-minute");
            if (httpEnabled && empty(allowedHttpHosts)) {
                throw invalid("HTTP tool requires a non-empty host allowlist");
            }
            if (httpEnabled) {
                validateHttpHosts();
            }
            if (dubboEnabled
                    && (empty(allowedDubboAddresses) || empty(allowedDubboMethods))) {
                throw invalid("Dubbo tool requires address and method allowlists");
            }
        }

        private void validateHttpHosts() {
            for (String value : allowedHttpHosts) {
                String host = value == null ? "" : value.trim().toLowerCase();
                if (!host.matches("[a-z0-9.-]+") || host.startsWith(".")
                        || host.endsWith(".") || host.contains("..")) {
                    throw invalid("HTTP allowlist entries must be exact host names");
                }
                if (host.equals("localhost") || host.endsWith(".localhost")
                        || host.startsWith("127.") || host.startsWith("169.254.")
                        || host.equals("0.0.0.0")
                        || host.equals("metadata.google.internal")) {
                    throw invalid("HTTP allowlist must not contain local or metadata hosts");
                }
            }
        }

        private boolean empty(Set<String> values) {
            return values == null || values.stream().noneMatch(NativeDiagnosisProperties.this::hasText);
        }
    }

    @Getter
    @Setter
    public class Backends {
        private String esBaseUrl = "";
        private String mysqlJdbcUrl = "";
        private String mysqlUser = "";
        private String mysqlPassword = "";
        private String redisHost = "";
        private int redisPort = 6379;
        private String redisPassword = "";
        private int redisDatabase;
        private String logQueryUrl = "";
        private LogQueryAdapter logQueryAdapter = LogQueryAdapter.HTTP;
        private String logQueryHealthUrl = "";
        private String logQueryDataSourceId = "";
        private String logQueryService = "";
        private Set<String> logQueryServiceAliases = Collections.emptySet();
        private Map<String, String> logQueryHeaders = new LinkedHashMap<String, String>();
        private int logQueryMaxRetries = 1;
        private long logQueryRetryMaxElapsedMs = 30000L;
        private long logQueryTimeoutMs = 30000L;
        private int logQueryMaxBodyBytes = 1048576;
        private int logQueryMaxResults = 500;
        private boolean logQueryLegacyTextAllowed;
        private String logQueryEsIndexPattern = "";
        private String logQueryEsTimestampField = "@timestamp";
        private String logQueryEsServiceField = "service.name";
        private String logQueryEsLevelField = "log.level";
        private String logQueryEsMessageField = "message";
        private String logQueryEsTraceField = "trace.id";
        private Map<String, String> logQueryEsFixedTermFilters =
                new LinkedHashMap<String, String>();
        private String logQueryLokiTenantId = "";
        private Map<String, String> logQueryLokiBaseSelector =
                new LinkedHashMap<String, String>();
        private String logQueryLokiServiceLabel = "service";
        private String logQueryLokiLevelLabel = "level";

        void validate() {
            boolean mysqlConfigured = hasText(mysqlJdbcUrl) || hasText(mysqlUser)
                    || hasText(mysqlPassword);
            if (mysqlConfigured && (!hasText(mysqlJdbcUrl) || !hasText(mysqlUser)
                    || !hasText(mysqlPassword))) {
                throw invalid("MySQL backend requires jdbc-url, user and password");
            }
            if (hasText(redisHost) && redisPort <= 0) {
                throw invalid("Redis backend port must be positive");
            }
            if (redisDatabase < 0) {
                throw invalid("Redis database must be non-negative");
            }
            validateRemoteLogQuery();
        }

        private void validateRemoteLogQuery() {
            if (!hasText(logQueryUrl)) {
                return;
            }
            requireText(logQueryHealthUrl, "backends.log-query-health-url");
            requireText(logQueryDataSourceId, "backends.log-query-data-source-id");
            requireText(logQueryService, "backends.log-query-service");
            requireHttpUri(logQueryUrl, "backends.log-query-url");
            requireHttpUri(logQueryHealthUrl, "backends.log-query-health-url");
            if (logQueryMaxRetries < 0 || logQueryMaxRetries > 1) {
                throw invalid("log-query-max-retries must be zero or one");
            }
            requirePositive(logQueryRetryMaxElapsedMs, "backends.log-query-retry-max-elapsed-ms");
            requirePositive(logQueryTimeoutMs, "backends.log-query-timeout-ms");
            requirePositive(logQueryMaxBodyBytes, "backends.log-query-max-body-bytes");
            if (logQueryMaxResults <= 0 || logQueryMaxResults > 500) {
                throw invalid("log-query-max-results must be between 1 and 500");
            }
            if (logQueryAdapter == null) {
                throw invalid("log-query-adapter must be configured");
            }
            if (logQueryAdapter == LogQueryAdapter.ELASTICSEARCH) {
                requireText(logQueryEsIndexPattern, "backends.log-query-es-index-pattern");
            }
            if (logQueryAdapter == LogQueryAdapter.LOKI) {
                requireText(logQueryLokiTenantId, "backends.log-query-loki-tenant-id");
            }
        }

        private void requireHttpUri(String value, String name) {
            try {
                URI uri = URI.create(value.trim());
                if (!("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getFragment() != null) {
                    throw invalid(name + " must be a safe absolute HTTP(S) URL");
                }
            } catch (IllegalArgumentException failure) {
                throw invalid(name + " must be a safe absolute HTTP(S) URL");
            }
        }
    }

    public enum LogQueryAdapter {
        HTTP,
        ELASTICSEARCH,
        LOKI
    }

    @Getter
    @Setter
    public class LocalLogs {
        private boolean enabled;
        private String root = "";
        private String service = "agent-web";
        private Set<String> serviceAliases = Collections.emptySet();
        private String dataSourceId = "local-agent-web-logs";
        private Set<String> allowedGlobs = Collections.singleton("*.log");
        private String logZone = "UTC";
        private int maxFiles = 20;
        private int maxDepth = 8;
        private int maxLines = 10000;
        private long maxBytes = 4194304L;
        private long maxScanDurationMs = 2000L;

        void validate() {
            if (!enabled) {
                return;
            }
            requireText(root, "local-logs.root");
            requireText(service, "local-logs.service");
            requireText(dataSourceId, "local-logs.data-source-id");
            validateRoot();
            validateGlobs();
            zoneId();
            requirePositive(maxFiles, "local-logs.max-files");
            requirePositive(maxDepth, "local-logs.max-depth");
            requirePositive(maxLines, "local-logs.max-lines");
            requirePositive(maxBytes, "local-logs.max-bytes");
            requirePositive(maxScanDurationMs, "local-logs.max-scan-duration-ms");
        }

        public ZoneId zoneId() {
            try {
                return ZoneId.of(logZone == null ? "" : logZone.trim());
            } catch (ZoneRulesException | IllegalArgumentException failure) {
                throw invalid("local-logs.log-zone must be a valid ZoneId");
            }
        }

        public Path rootPath() {
            return Path.of(root.trim()).normalize();
        }

        private void validateRoot() {
            Path path;
            try {
                path = rootPath();
            } catch (RuntimeException failure) {
                throw invalid("local-logs.root must be a readable absolute directory");
            }
            if (!path.isAbsolute() || !Files.isDirectory(path) || !Files.isReadable(path)) {
                throw invalid("local-logs.root must be a readable absolute directory");
            }
        }

        private void validateGlobs() {
            if (allowedGlobs == null || allowedGlobs.isEmpty()
                    || allowedGlobs.stream().anyMatch(this::unsafeGlob)) {
                throw invalid("local-logs.allowed-globs must contain safe relative globs");
            }
        }

        private boolean unsafeGlob(String glob) {
            return !hasText(glob) || glob.contains("..")
                    || glob.startsWith("/") || glob.startsWith("\\");
        }
    }
}
