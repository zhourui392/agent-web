package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.DomainText;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 与存储格式无关的 Skill Manifest 领域描述。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class SkillManifest {

    private final String id;
    private final String version;
    private final String description;
    private final Set<String> applicableUseCases;
    private final Set<String> techTags;
    private final Set<String> explicitTriggers;
    private final String entryPath;
    private final Set<String> resourcePaths;
    private final List<SkillDependency> dependencies;
    private final Set<String> conflicts;
    private final Set<String> compatibleRuntimes;
    private final SkillTrustSource trustSource;
    private final List<CapabilityRequest> capabilityRequests;

    public SkillManifest(String id, String version, String description,
                         Set<String> applicableUseCases, Set<String> techTags,
                         Set<String> explicitTriggers, String entryPath, Set<String> resourcePaths,
                         List<SkillDependency> dependencies, Set<String> conflicts,
                         Set<String> compatibleRuntimes, SkillTrustSource trustSource,
                         List<CapabilityRequest> capabilityRequests) {
        this.id = DomainText.require(id, "skill id", 120);
        this.version = DomainText.require(version, "skill version", 60);
        this.description = DomainText.require(description, "skill description", 500);
        this.applicableUseCases = immutableUppercase(applicableUseCases, "applicable use case");
        this.techTags = immutableLowercase(techTags, "technical tag");
        this.explicitTriggers = immutable(explicitTriggers, "explicit trigger", 120);
        this.entryPath = DomainText.require(entryPath, "skill entry path", 500);
        this.resourcePaths = immutable(resourcePaths, "skill resource path", 500);
        this.dependencies = immutableList(dependencies, "skill dependencies");
        this.conflicts = immutable(conflicts, "skill conflict", 120);
        this.compatibleRuntimes = immutableUppercase(compatibleRuntimes, "skill runtime");
        if (trustSource == null) {
            throw new IllegalArgumentException("skill trust source must not be null");
        }
        this.trustSource = trustSource;
        this.capabilityRequests = immutableList(capabilityRequests, "capability requests");
    }

    public boolean supports(String useCase, String runtime) {
        return applicableUseCases.contains(normalize(useCase, "capability use case"))
                && compatibleRuntimes.contains(normalize(runtime, "capability runtime"));
    }

    public boolean matchesAnyTag(Set<String> tags) {
        for (String tag : tags) {
            if (techTags.contains(tag.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> immutableLowercase(Set<String> values, String name) {
        Set<String> copy = new LinkedHashSet<String>();
        for (String value : requireSet(values, name)) {
            copy.add(DomainText.require(value, name, 120).toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(copy);
    }

    private Set<String> immutableUppercase(Set<String> values, String name) {
        Set<String> copy = new LinkedHashSet<String>();
        for (String value : requireSet(values, name)) {
            copy.add(normalize(value, name));
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return Collections.unmodifiableSet(copy);
    }

    private String normalize(String value, String name) {
        return DomainText.require(value, name, 120).toUpperCase(Locale.ROOT);
    }

    private Set<String> immutable(Set<String> values, String name, int maxLength) {
        Set<String> copy = new LinkedHashSet<String>();
        for (String value : requireSet(values, name)) {
            copy.add(DomainText.require(value, name, maxLength));
        }
        return Collections.unmodifiableSet(copy);
    }

    private Set<String> requireSet(Set<String> values, String name) {
        if (values == null) {
            throw new IllegalArgumentException(name + " set must not be null");
        }
        return values;
    }

    private <T> List<T> immutableList(List<T> values, String name) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(name + " must not be null or contain null");
        }
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
