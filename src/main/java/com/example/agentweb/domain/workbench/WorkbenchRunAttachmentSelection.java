package com.example.agentweb.domain.workbench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Run 请求中类型化附件身份的统一数量和唯一性规则。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class WorkbenchRunAttachmentSelection {

    public static final int MAXIMUM_ATTACHMENTS = 8;

    private WorkbenchRunAttachmentSelection() {
    }

    public static List<WorkbenchRunAttachmentReference> immutable(
            List<WorkbenchRunAttachmentReference> values) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(
                    "workbench run attachments must be complete");
        }
        if (values.size() > MAXIMUM_ATTACHMENTS) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                    "workbench run accepts at most eight combined attachments");
        }
        Set<String> unique = new HashSet<String>();
        for (WorkbenchRunAttachmentReference value : values) {
            if (!unique.add(value.logicalIdentity())) {
                throw new WorkbenchDomainException(
                        WorkbenchErrorCode.ATTACHMENT_INVALID,
                        "workbench run attachment identities must be unique");
            }
        }
        return Collections.unmodifiableList(
                new ArrayList<WorkbenchRunAttachmentReference>(values));
    }
}
