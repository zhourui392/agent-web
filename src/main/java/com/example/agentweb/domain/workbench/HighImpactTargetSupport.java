package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workspace.RepositorySelection;

import java.util.Collections;
import java.util.regex.Pattern;

/**
 * 高影响 Target 共享的 Git 标识和 Payload Hash framing。
 *
 * @author alex
 * @since 2026-08-01
 */
final class HighImpactTargetSupport {

    private static final Pattern GIT_OBJECT_ID = Pattern.compile("[a-f0-9]{40}|[a-f0-9]{64}");

    private HighImpactTargetSupport() {
    }

    static String repositoryKey(String value) {
        return RepositorySelection.of(value, Collections.singletonList(value))
                .getPrimaryRepositoryKey();
    }

    static String gitObjectId(String value, String name) {
        String normalized = DomainText.require(value, name, 64).toLowerCase();
        if (!GIT_OBJECT_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a Git object id");
        }
        return normalized;
    }

    static String payloadHash(String type, PayloadAppender appender) {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "schema", "high-impact-operation-target@1");
        CanonicalHashing.appendFramed(canonical, "type", type);
        appender.appendTo(canonical);
        return CanonicalHashing.sha256(canonical.toString());
    }

    interface PayloadAppender {
        void appendTo(StringBuilder canonical);
    }
}
