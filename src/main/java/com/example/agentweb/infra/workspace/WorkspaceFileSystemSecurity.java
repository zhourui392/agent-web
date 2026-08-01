package com.example.agentweb.infra.workspace;

import com.example.agentweb.domain.shared.CanonicalHashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Workspace 真实根身份与 Git 入口的公共文件系统检查。
 *
 * @author alex
 * @since 2026-08-01
 */
final class WorkspaceFileSystemSecurity {

    private WorkspaceFileSystemSecurity() {
    }

    static boolean hasGitEntry(Path repository) {
        Path gitEntry = repository.resolve(".git");
        return Files.isDirectory(gitEntry, LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(gitEntry, LinkOption.NOFOLLOW_LINKS);
    }

    static String rootFingerprint(Path repositoryRealRoot) throws IOException {
        Path gitEntry = repositoryRealRoot.resolve(".git");
        BasicFileAttributes rootAttributes = Files.readAttributes(
                repositoryRealRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        BasicFileAttributes gitAttributes = Files.readAttributes(
                gitEntry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "repositoryRoot",
                repositoryRealRoot.toString());
        CanonicalHashing.appendFramed(canonical, "repositoryFileKey",
                String.valueOf(rootAttributes.fileKey()));
        CanonicalHashing.appendFramed(canonical, "repositoryCreationTime",
                rootAttributes.creationTime().toString());
        CanonicalHashing.appendFramed(canonical, "gitEntryKind",
                gitAttributes.isDirectory() ? "DIRECTORY" : "FILE");
        CanonicalHashing.appendFramed(canonical, "gitEntryFileKey",
                String.valueOf(gitAttributes.fileKey()));
        CanonicalHashing.appendFramed(canonical, "gitEntryCreationTime",
                gitAttributes.creationTime().toString());
        if (gitAttributes.isDirectory()) {
            appendStableDirectoryIdentity(canonical, gitEntry.resolve("objects"));
        }
        return CanonicalHashing.sha256(canonical.toString());
    }

    private static void appendStableDirectoryIdentity(StringBuilder canonical, Path directory)
            throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        CanonicalHashing.appendFramed(canonical, "gitObjectsFileKey",
                String.valueOf(attributes.fileKey()));
        CanonicalHashing.appendFramed(canonical, "gitObjectsCreationTime",
                attributes.creationTime().toString());
    }
}
