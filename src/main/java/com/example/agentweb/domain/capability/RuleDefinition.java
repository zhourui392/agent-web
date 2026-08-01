package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 不绑定任何消费方阶段或质量门语义的版本化规则定义。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuleDefinition {

    private final String id;
    private final String version;
    private final String source;
    private final boolean mandatory;
    private final String summary;
    private final Set<String> applicableUseCases;
    private final List<RuleResource> resources;
    private final String contentHash;
    private final Map<String, RuleResource> resourcesByName;

    public RuleDefinition(String id, String version, String source, boolean mandatory, String summary,
                          Set<String> applicableUseCases, List<RuleResource> resources,
                          String contentHash) {
        this.id = DomainText.require(id, "rule id", 160);
        this.version = DomainText.require(version, "rule version", 80);
        this.source = DomainText.require(source, "rule source", 120);
        this.mandatory = mandatory;
        this.summary = DomainText.require(summary, "rule summary", 500);
        this.applicableUseCases = useCases(applicableUseCases);
        if (resources == null || resources.isEmpty() || resources.contains(null)) {
            throw new IllegalArgumentException("rule resources must not be empty or contain null");
        }
        Map<String, RuleResource> indexed = new LinkedHashMap<String, RuleResource>();
        for (RuleResource resource : resources) {
            if (indexed.put(resource.getName(), resource) != null) {
                throw new IllegalArgumentException(
                        "rule definition contains duplicate resource: " + resource.getName());
            }
        }
        this.resources = Collections.unmodifiableList(new ArrayList<RuleResource>(resources));
        this.resourcesByName = Collections.unmodifiableMap(indexed);
        this.contentHash = DomainText.requireSha256(contentHash, "rule content hash");
    }

    public boolean supports(String useCase) {
        return applicableUseCases.contains(DomainText.require(
                useCase, "capability use case", 120).toUpperCase(Locale.ROOT));
    }

    public RuleResource requireResource(String name) {
        RuleResource resource = resourcesByName.get(name);
        if (resource == null) {
            throw new IllegalArgumentException("required rule resource is missing: " + name);
        }
        return resource;
    }

    private Set<String> useCases(Set<String> values) {
        if (values == null || values.isEmpty() || values.contains(null)) {
            throw new IllegalArgumentException("rule use cases must not be empty or contain null");
        }
        Set<String> copy = new LinkedHashSet<String>();
        for (String value : values) {
            copy.add(DomainText.require(value, "rule use case", 120).toUpperCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(copy);
    }
}
