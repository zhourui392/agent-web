package com.example.agentweb.domain.worktree;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把一次会话的「工作目录」解析为承载上传文件的「稳定 workspace 根」。
 *
 * <p>worktree 会话的 {@code workingDir} 落在短命的 {@code .worktrees/...} 子树内
 * (例 {@code /ws/.worktrees/u-x/branch}),而引用上传文件的聊天会话生命周期更长 ——
 * 直接把图片/附件存进 worktree,删分支即随 {@code git worktree remove} 一并丢失。
 * 故凡 {@code workingDir} 落在 {@code .worktrees} 之内,一律把上传根上提到其之上的 workspace 根
 * (例 {@code /ws}),使上传文件不随建删分支而消亡;非 worktree 路径本就稳定,原样返回。</p>
 *
 * <p>纯路径规则,不触碰文件系统;{@link com.example.agentweb.app.worktree.WorktreeAppService} 复用此处的
 * {@link #WORKTREE_DIR} 常量,避免目录约定在两处漂移。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-17
 */
public final class WorkspaceUploadRoot {

    /** worktree 子树的固定目录名;workspace 根即此目录之上的那一级。 */
    public static final String WORKTREE_DIR = ".worktrees";

    private WorkspaceUploadRoot() {
    }

    /**
     * 解析上传根:{@code workingDir} 命中 {@code .worktrees} 段时上提到其上一级 workspace 根,
     * 否则原样返回。
     *
     * @param workingDir 会话工作目录(可能是 worktree 内路径);null/空白时按原样返回(调用方已前置校验)
     * @return 稳定的上传根目录路径
     */
    public static String resolve(String workingDir) {
        if (workingDir == null || workingDir.trim().isEmpty()) {
            return workingDir;
        }
        Path p = Paths.get(workingDir).normalize();
        Path root = p.getRoot();
        for (int i = 0; i < p.getNameCount(); i++) {
            if (WORKTREE_DIR.equals(p.getName(i).toString())) {
                if (i == 0) {
                    // .worktrees 之上无 workspace 根,无处上提,防御性原样返回
                    return workingDir;
                }
                Path prefix = p.subpath(0, i);
                Path base = (root == null) ? prefix : root.resolve(prefix);
                return base.toString();
            }
        }
        return workingDir;
    }
}
