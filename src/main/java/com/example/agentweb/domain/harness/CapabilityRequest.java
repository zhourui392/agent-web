package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.Objects;

/**
 * Skill 声明的逻辑文件或命令能力请求，不等价于授权。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class CapabilityRequest {

    private final CapabilityKind kind;
    private final CapabilityAccess access;
    private final String resource;

    public CapabilityRequest(CapabilityKind kind, CapabilityAccess access, String resource) {
        if (kind == null || access == null) {
            throw new IllegalArgumentException("capability kind and access must not be null");
        }
        if (kind == CapabilityKind.COMMAND && access != CapabilityAccess.EXECUTE) {
            throw new IllegalArgumentException("command capability must use EXECUTE access");
        }
        if (kind == CapabilityKind.FILE && access == CapabilityAccess.EXECUTE) {
            throw new IllegalArgumentException("file capability cannot use EXECUTE access");
        }
        this.kind = kind;
        this.access = access;
        this.resource = DomainText.require(resource, "capability resource", 500);
    }

    public static CapabilityRequest fileRead(String logicalRoot) {
        return new CapabilityRequest(CapabilityKind.FILE, CapabilityAccess.READ, logicalRoot);
    }

    public static CapabilityRequest fileWrite(String logicalRoot) {
        return new CapabilityRequest(CapabilityKind.FILE, CapabilityAccess.WRITE, logicalRoot);
    }

    public static CapabilityRequest command(String logicalCommand) {
        return new CapabilityRequest(CapabilityKind.COMMAND, CapabilityAccess.EXECUTE, logicalCommand);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CapabilityRequest)) {
            return false;
        }
        CapabilityRequest that = (CapabilityRequest) other;
        return kind == that.kind && access == that.access && resource.equals(that.resource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, access, resource);
    }
}
