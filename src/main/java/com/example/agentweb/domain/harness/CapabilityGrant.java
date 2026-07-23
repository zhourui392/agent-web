package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Run/Attempt 级显式能力授权，使用逻辑名避免 Domain 依赖文件系统。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class CapabilityGrant {

    private final Set<String> readableFileRoots;
    private final Set<String> writableFileRoots;
    private final Set<String> executableCommands;

    public CapabilityGrant(Set<String> readableFileRoots, Set<String> writableFileRoots,
                           Set<String> executableCommands) {
        this.readableFileRoots = immutable(readableFileRoots, "readable file root");
        this.writableFileRoots = immutable(writableFileRoots, "writable file root");
        this.executableCommands = immutable(executableCommands, "executable command");
    }

    public static CapabilityGrant none() {
        return new CapabilityGrant(Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet());
    }

    public boolean permits(CapabilityRequest request) {
        if (request.getKind() == CapabilityKind.COMMAND) {
            return executableCommands.contains(request.getResource());
        }
        if (request.getAccess() == CapabilityAccess.READ) {
            return readableFileRoots.contains(request.getResource());
        }
        return writableFileRoots.contains(request.getResource());
    }

    private Set<String> immutable(Set<String> values, String name) {
        if (values == null) {
            throw new IllegalArgumentException(name + " set must not be null");
        }
        Set<String> copy = new LinkedHashSet<String>();
        for (String value : values) {
            copy.add(DomainText.require(value, name, 500));
        }
        return Collections.unmodifiableSet(copy);
    }
}
