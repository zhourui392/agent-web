package com.example.agentweb.app.worktree;

import java.io.File;
import java.nio.file.Path;

/**
 * workspace 下发现的一个 git 仓库。
 *
 * @param dir          仓库根目录
 * @param relativePath 相对 workspace 的路径, 作为仓库的逻辑名(可区分不同父目录下的同名叶子仓库)
 */
public record GitRepoEntry(File dir, Path relativePath) {
}
