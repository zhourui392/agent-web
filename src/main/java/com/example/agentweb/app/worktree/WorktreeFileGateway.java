package com.example.agentweb.app.worktree;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * worktree 文件系统操作端口。隔离编排(app 层)与目录遍历 / 链接 / 删除等 FS 副作用(infra 层)。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface WorktreeFileGateway {

    /**
     * 扫描 workspace 下的 git 仓库(限深、跳过隐藏目录), 按相对路径排序返回。
     */
    List<GitRepoEntry> collectGitRepos(Path workspace) throws IOException;

    /**
     * 遍历 worktree base, 把叶子分为链接(symlink / junction)与真实 worktree。
     */
    WorktreeLeaves classifyLeaves(Path base) throws IOException;

    /**
     * 仓库是否配置了远端(读 {@code .git/config}); 判断失败按有远端处理。
     */
    boolean hasConfiguredRemote(File repoDir);

    void createDirectories(Path dir) throws IOException;

    boolean isDirectory(Path path);

    /** 跟随链接判断存在性。 */
    boolean exists(Path path);

    /** 不跟随链接判断存在性(用于区分链接叶子与真实目录)。 */
    boolean existsNoFollowLinks(Path path);

    /** 删除单个文件或链接(非递归)。 */
    void delete(Path path) throws IOException;

    /** 递归删除目录树。 */
    void deleteRecursively(Path path) throws IOException;

    /**
     * sourceFile 是常规文件时复制到 targetDir 下同名文件(覆盖、保留属性), 否则什么都不做。
     */
    void copyFileIfPresent(Path sourceFile, Path targetDir) throws IOException;

    /**
     * 建目录链接: Unix symlink, Windows NTFS junction(无需管理员权限)。
     */
    void createDirectoryLink(Path link, Path target) throws IOException, InterruptedException;
}
