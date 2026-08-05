package com.example.agentweb.domain.workbench.context;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchStageRunPreparationPlan;
import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 单次 Dynamic Stage Run 可见的 Workbench 全局上下文元数据清单。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchContextManifest {

    private static final int MAXIMUM_PROMPT_BYTES = 256 * 1024;

    private final WorkbenchId workbenchId;
    private final long contextVersion;
    private final String contextHash;
    private final List<WorkbenchContextDocumentSnapshot> documents;
    private final String promptContent;

    private WorkbenchContextManifest(
            WorkbenchId workbenchId, long contextVersion,
            String contextHash,
            List<WorkbenchContextDocumentSnapshot> documents,
            String promptContent) {
        if (workbenchId == null || contextVersion < 0L) {
            throw new IllegalArgumentException(
                    "Workbench Context Manifest identity and version are required");
        }
        this.workbenchId = workbenchId;
        this.contextVersion = contextVersion;
        this.contextHash = DomainText.requireSha256(
                contextHash, "Workbench Context Manifest Hash");
        this.documents = immutableDocuments(documents);
        this.promptContent = DomainText.require(
                promptContent, "Workbench Context Manifest prompt", 256 * 1024);
        if (this.promptContent.getBytes(StandardCharsets.UTF_8).length
                > MAXIMUM_PROMPT_BYTES) {
            throw new IllegalArgumentException(
                    "Workbench Context Manifest exceeds 256 KiB");
        }
    }

    public static WorkbenchContextManifest freeze(
            WorkbenchId workbenchId, long contextVersion,
            String contextHash,
            List<WorkbenchContextDocumentSnapshot> documents,
            String promptContent) {
        return new WorkbenchContextManifest(
                workbenchId, contextVersion, contextHash,
                documents, promptContent);
    }

    public void requireCurrent(WorkbenchStageRunPreparationPlan plan) {
        if (plan == null || !workbenchId.equals(plan.getWorkbenchId())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    private static List<WorkbenchContextDocumentSnapshot> immutableDocuments(
            List<WorkbenchContextDocumentSnapshot> source) {
        if (source == null || source.contains(null)) {
            throw new IllegalArgumentException(
                    "Workbench Context Manifest documents are required");
        }
        List<WorkbenchContextDocumentSnapshot> copy =
                new ArrayList<WorkbenchContextDocumentSnapshot>(source);
        Set<String> identifiers = new HashSet<String>();
        for (WorkbenchContextDocumentSnapshot document : copy) {
            if (!identifiers.add(document.getContextDocumentIdentifier())) {
                throw new IllegalArgumentException(
                        "Workbench Context Manifest contains duplicate document");
            }
        }
        return Collections.unmodifiableList(copy);
    }
}
