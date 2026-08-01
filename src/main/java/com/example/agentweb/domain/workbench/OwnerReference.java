package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.Objects;

/**
 * 实际登录用户的领域引用；授权以稳定 ownerId 为准，名称只用于安全展示。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class OwnerReference {

    private final String ownerId;
    private final String ownerName;

    private OwnerReference(String ownerId, String ownerName) {
        this.ownerId = DomainText.require(ownerId, "owner id", 128);
        this.ownerName = DomainText.require(ownerName, "owner name", 256);
    }

    public static OwnerReference of(String ownerId, String ownerName) {
        return new OwnerReference(ownerId, ownerName);
    }

    public boolean sameIdentityAs(OwnerReference other) {
        return other != null && ownerId.equals(other.ownerId);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OwnerReference)) {
            return false;
        }
        OwnerReference that = (OwnerReference) other;
        return ownerId.equals(that.ownerId) && ownerName.equals(that.ownerName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerId, ownerName);
    }
}
