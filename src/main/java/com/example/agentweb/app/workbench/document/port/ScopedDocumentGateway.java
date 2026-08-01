package com.example.agentweb.app.workbench.document.port;

import com.example.agentweb.app.workbench.document.DocumentContentView;
import com.example.agentweb.app.workbench.document.DocumentDirectoryQuery;
import com.example.agentweb.app.workbench.document.DocumentDirectoryView;
import com.example.agentweb.app.workbench.document.DocumentDownloadView;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workspace.RepositoryScope;

/**
 * 冻结 Repository Scope 内的只读文档访问端口。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface ScopedDocumentGateway {

    DocumentDirectoryView listTree(
            RepositoryScope scope, DocumentDirectoryQuery query);

    DocumentContentView readContent(
            RepositoryScope scope, DocumentReference reference);

    DocumentDownloadView download(
            RepositoryScope scope, DocumentReference reference);

    DocumentDownloadView inlineImage(
            RepositoryScope scope, DocumentReference reference);
}
