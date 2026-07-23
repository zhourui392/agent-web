package com.example.agentweb.domain.worktree;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 单仓库 Worktree 聚合根。身份由用户、仓库与逻辑分支共同确定，物理路径和当前检出 ref
 * 是其生命周期状态。
 *
 * @author alex
 * @since 2026-07-23
 */
public final class Worktree {

    private final String id;
    private final String userSlug;
    private final String repoName;
    private final String branch;
    private final Path path;
    private final String checkedOutRef;
    private final int repoLeafCount;

    private Worktree(String userSlug, String repoName, String branch, Path path,
                     String checkedOutRef, int repoLeafCount) {
        this.userSlug = requireText(userSlug, "userSlug");
        this.repoName = requireText(repoName, "repoName");
        this.branch = requireText(branch, "branch");
        if (path == null) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (repoLeafCount < 0) {
            throw new IllegalArgumentException("repoLeafCount 不能小于 0");
        }
        this.path = path.normalize();
        this.checkedOutRef = checkedOutRef;
        this.repoLeafCount = repoLeafCount;
        this.id = this.userSlug + ":" + this.repoName + ":" + this.branch;
    }

    /** 从 Git/文件系统已存在状态恢复聚合。 */
    public static Worktree restore(String userSlug, String repoName, String branch, Path path,
                                   String checkedOutRef, int repoLeafCount) {
        return new Worktree(userSlug, repoName, branch, path, checkedOutRef, repoLeafCount);
    }

    /**
     * 返回可安全回收的当前用户私有 ref；真实业务分支和 detached HEAD 不参与 ref 回收。
     */
    public Optional<String> removablePrivateRef() {
        if (!UserBranchRef.isNamespaced(checkedOutRef)) {
            return Optional.empty();
        }
        return Optional.of(requireRemovable());
    }

    /**
     * 强制校验当前 ref 属于本聚合用户的私有命名空间，防止误删真实分支或其他用户分支。
     *
     * @return 已校验的私有 ref
     */
    public String requireRemovable() {
        String ownerPrefix = UserBranchRef.PREFIX + userSlug + "/";
        if (checkedOutRef == null || !checkedOutRef.startsWith(ownerPrefix)
                || checkedOutRef.length() == ownerPrefix.length()) {
            throw new IllegalStateException("Worktree ref 不允许回收: " + checkedOutRef);
        }
        return checkedOutRef;
    }

    public String id() {
        return id;
    }

    public String userSlug() {
        return userSlug;
    }

    public String repoName() {
        return repoName;
    }

    public String branch() {
        return branch;
    }

    public Path path() {
        return path;
    }

    public String checkedOutRef() {
        return checkedOutRef;
    }

    public int repoLeafCount() {
        return repoLeafCount;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
