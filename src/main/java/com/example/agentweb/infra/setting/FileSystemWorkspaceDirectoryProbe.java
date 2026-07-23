package com.example.agentweb.infra.setting;

import com.example.agentweb.domain.setting.WorkspaceDirectoryProbe;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 基于本机文件系统的工作空间目录状态探针。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Component
public class FileSystemWorkspaceDirectoryProbe implements WorkspaceDirectoryProbe {

    @Override
    public boolean isExistingDirectory(String path) {
        return Files.isDirectory(Paths.get(path));
    }
}
