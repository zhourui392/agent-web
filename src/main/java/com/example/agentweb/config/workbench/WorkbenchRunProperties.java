package com.example.agentweb.config.workbench;

import com.example.agentweb.domain.capability.SkillTrustSource;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Workbench Run 准备与 Runtime 的生产技术限额。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
@ConfigurationProperties(prefix = "agent.workbench.run")
@Getter
@Setter
public class WorkbenchRunProperties {

    private long timeoutSeconds = 1800L;
    private long maxOutputBytes = 8L * 1024L * 1024L;
    private Set<SkillTrustSource> allowedSkillTrustSources =
            new LinkedHashSet<SkillTrustSource>(
                    Collections.singleton(SkillTrustSource.PLATFORM));

    @PostConstruct
    public void validate() {
        if (timeoutSeconds < 1L || maxOutputBytes < 1L
                || allowedSkillTrustSources == null
                || allowedSkillTrustSources.contains(null)) {
            throw new IllegalStateException(
                    "Invalid Workbench run configuration");
        }
    }
}
