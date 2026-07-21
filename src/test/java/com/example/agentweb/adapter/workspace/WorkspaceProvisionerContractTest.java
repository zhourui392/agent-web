package com.example.agentweb.adapter.workspace;

import com.example.agentweb.domain.workspace.DirtyReport;
import com.example.agentweb.support.GitRepoFixture;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorkspaceProvisioner 端口契约基类（verification-plan §三）：git worktree 实现
 * 与 M3 容器实现共享三条契约用例，实现类只需提供 provisioner 与新远端仓的构造方式。
 *
 * <p>测试方法必须 public：子类跨包继承时 JUnit 才能发现（package-private 跨包不可继承）。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public abstract class WorkspaceProvisionerContractTest {

    protected abstract WorkspaceProvisioner provisioner();

    /** 实现负责造一个带初始提交的远端仓，返回可 clone 的 URL。 */
    protected abstract String newRemoteRepoUrl();

    @Test
    public void provision_twice_with_same_request_should_return_same_worktree() {
        WorkspaceProvisioner provisioner = provisioner();
        ProvisionRequest request = newRequest(newRemoteRepoUrl());

        ProvisionedWorkspace first = provisioner.provision(request);
        ProvisionedWorkspace second = provisioner.provision(request);

        assertThat(Path.of(second.getWorktreePath()))
                .isEqualTo(Path.of(first.getWorktreePath()));
        assertThat(Path.of(second.getWorktreePath())).isDirectory();
    }

    @Test
    public void release_should_remove_worktree_path() {
        WorkspaceProvisioner provisioner = provisioner();
        ProvisionRequest request = newRequest(newRemoteRepoUrl());
        ProvisionedWorkspace workspace = provisioner.provision(request);

        provisioner.release(new ReleaseRequest(workspace.getMirrorPath(),
                workspace.getWorktreePath(), request.getBranch(), false, false));

        assertThat(Path.of(workspace.getWorktreePath())).doesNotExist();
    }

    @Test
    public void detect_dirty_should_report_clean_uncommitted_and_unpushed_states() throws Exception {
        WorkspaceProvisioner provisioner = provisioner();
        ProvisionRequest request = newRequest(newRemoteRepoUrl());
        ProvisionedWorkspace workspace = provisioner.provision(request);
        Path worktree = Path.of(workspace.getWorktreePath());

        DirtyReport clean = provisioner.detectDirty(workspace.getWorktreePath());
        assertThat(clean.isDirty()).isFalse();

        Files.writeString(worktree.resolve("dirty.txt"), "uncommitted");
        DirtyReport uncommitted = provisioner.detectDirty(workspace.getWorktreePath());
        assertThat(uncommitted.getUncommittedFiles()).isNotEmpty();

        GitRepoFixture.git(worktree, "add", "-A");
        GitRepoFixture.git(worktree, "-c", "user.name=t", "-c", "user.email=t@test.local",
                "-c", "commit.gpgsign=false", "commit", "-m", "local only");
        DirtyReport unpushed = provisioner.detectDirty(workspace.getWorktreePath());
        assertThat(unpushed.getUnpushedCommits()).isGreaterThan(0);
    }

    private ProvisionRequest newRequest(String repoUrl) {
        String requirementId = "REQ-" + UUID.randomUUID().toString().substring(0, 8);
        return new ProvisionRequest(repoUrl, requirementId, "req/" + requirementId, null);
    }
}
