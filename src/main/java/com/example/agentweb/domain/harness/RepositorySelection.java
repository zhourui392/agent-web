package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 用户确认的仓库相对路径集合及主仓库；作为创建 Run 的业务输入。
 *
 * <p>不变量见 05-multi-repository-workspace-design.md §6.1。
 *
 * @author zhourui(V33215020)
 * @since 2026-08-01
 */
@Getter
public final class RepositorySelection {

    private final String primaryRepositoryKey;
    private final List<String> repositoryKeys;

    private RepositorySelection(String primaryRepositoryKey, List<String> repositoryKeys) {
        this.primaryRepositoryKey = primaryRepositoryKey;
        this.repositoryKeys = Collections.unmodifiableList(repositoryKeys);
    }

    /**
     * 规范化仓库相对路径并校验选择不变量。
     *
     * @param primaryRepositoryKey 主仓库 key（与集合中某一规范化路径相同）
     * @param repositoryKeys       仓库相对路径集合（允许未排序、未规范化）
     * @return 规范化、字典序排序后的不可变选择
     */
    public static RepositorySelection of(String primaryRepositoryKey, List<String> repositoryKeys) {
        if (repositoryKeys == null || repositoryKeys.isEmpty()) {
            throw new IllegalArgumentException("repository selection must contain at least one repository");
        }
        if (repositoryKeys.contains(null)) {
            throw new IllegalArgumentException("repository selection must not contain null keys");
        }

        Set<String> normalized = new LinkedHashSet<String>();
        for (String raw : repositoryKeys) {
            String key = normalizeRepositoryKey(raw);
            if (!normalized.add(key)) {
                throw new IllegalArgumentException(
                        "repository selection contains duplicate repository key: " + key);
            }
        }
        rejectNestedRepositories(normalized);

        String primary = normalizeRepositoryKey(primaryRepositoryKey);
        if (!normalized.contains(primary)) {
            throw new IllegalArgumentException(
                    "primary repository must be one of the selected repositories");
        }

        List<String> ordered = new ArrayList<String>(normalized);
        Collections.sort(ordered);
        return new RepositorySelection(primary, ordered);
    }

    public boolean contains(String repositoryKey) {
        if (repositoryKey == null || repositoryKey.trim().isEmpty()) {
            return false;
        }
        try {
            return repositoryKeys.contains(normalizeRepositoryKey(repositoryKey));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public int size() {
        return repositoryKeys.size();
    }

    static String normalizeRepositoryKey(String raw) {
        String value = DomainText.require(raw, "repository key", 512);
        String unified = value.replace('\\', '/');
        while (unified.startsWith("./")) {
            unified = unified.substring(2);
        }
        while (unified.endsWith("/") && unified.length() > 1) {
            unified = unified.substring(0, unified.length() - 1);
        }
        if (unified.isEmpty() || ".".equals(unified) || "..".equals(unified)) {
            throw new IllegalArgumentException("repository key must not be empty, '.' or '..'");
        }
        if (unified.startsWith("/") || unified.matches("(?i)^[a-z]:(/|$).*")) {
            throw new IllegalArgumentException("repository key must be a relative path: " + unified);
        }
        String[] segments = unified.split("/");
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(
                        "repository key must not contain empty, '.' or '..' segments: " + unified);
            }
        }
        return unified;
    }

    private static void rejectNestedRepositories(Set<String> keys) {
        List<String> ordered = new ArrayList<String>(keys);
        Collections.sort(ordered);
        for (int i = 0; i < ordered.size(); i++) {
            String outer = ordered.get(i);
            String prefix = outer + "/";
            for (int j = i + 1; j < ordered.size(); j++) {
                String inner = ordered.get(j);
                if (inner.startsWith(prefix)) {
                    throw new IllegalArgumentException(
                            "repository selection must not contain nested repositories: "
                                    + outer + " contains " + inner);
                }
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RepositorySelection)) {
            return false;
        }
        RepositorySelection that = (RepositorySelection) other;
        return primaryRepositoryKey.equals(that.primaryRepositoryKey)
                && repositoryKeys.equals(that.repositoryKeys);
    }

    @Override
    public int hashCode() {
        return 31 * primaryRepositoryKey.hashCode() + repositoryKeys.hashCode();
    }

    @Override
    public String toString() {
        return "RepositorySelection{primary=" + primaryRepositoryKey
                + ", repositories=" + repositoryKeys + '}';
    }
}
