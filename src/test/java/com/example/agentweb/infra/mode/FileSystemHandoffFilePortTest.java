package com.example.agentweb.infra.mode;

import com.example.agentweb.infra.workspace.FileSystemWorkspaceHandoffGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交接文件端口回归（@TempDir 真实文件系统）。
 *
 * @author alex
 * @since 2026-08-20
 */
class FileSystemHandoffFilePortTest {

    @TempDir
    Path tempDir;

    private final FileSystemHandoffFilePort port =
            new FileSystemHandoffFilePort(new FileSystemWorkspaceHandoffGuard());

    @Test
    void exportWritesFileAndGitignoreEntry() throws Exception {
        String relative = port.export(tempDir.toString(), "h1", "# 交接\n内容");
        assertEquals(".workbench/handoff/h1.md", relative);
        Path file = tempDir.resolve(relative);
        assertTrue(Files.isRegularFile(file));
        assertEquals("# 交接\n内容", port.readContent(tempDir.toString(), relative));
        String gitignore = Files.readString(tempDir.resolve(".gitignore"));
        assertTrue(gitignore.contains(".workbench/handoff/"));
    }

    @Test
    void readMissingFileReturnsNull() {
        assertNull(port.readContent(tempDir.toString(), ".workbench/handoff/nope.md"));
        assertNull(port.readContent(tempDir.toString(), null));
    }

    @Test
    void deleteQuietlyRemovesCompensationTarget() {
        String relative = port.export(tempDir.toString(), "h2", "x");
        port.deleteQuietly(tempDir.toString(), relative);
        assertTrue(!Files.exists(tempDir.resolve(relative)));
    }

    @Test
    void traversalPathIsRejected() {
        assertNull(port.readContent(tempDir.toString(), "../outside.md"));
        port.deleteQuietly(tempDir.toString(), "../outside.md");
        assertTrue(!Files.exists(tempDir.resolve("../outside.md")));
    }
}
