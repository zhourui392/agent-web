package com.example.agentweb.infra.workspace;

import com.example.agentweb.adapter.workspace.ProvisionRequest;
import com.example.agentweb.adapter.workspace.ProvisionedWorkspace;
import com.example.agentweb.adapter.workspace.ReleaseRequest;
import com.example.agentweb.adapter.workspace.WorkspaceProvisioner;
import com.example.agentweb.adapter.workspace.WorkspaceProvisionerContractTest;
import com.example.agentweb.support.GitRepoFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GitWorktreeProvisioner 集成测试（真裸仓 @TempDir，不起 Spring）：继承端口契约
 * 三条 + 专属用例。最关键哨兵：禁 clone --mirror —— 二次 provision 触发
 * fetch --prune 后本地 req/* 分支必须存活，refspec 必须只落 refs/remotes/。
 *
 * <p>路径断言统一走 java.nio.Path 比较，规避 Windows 分隔符差异。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
class GitWorktreeProvisionerTest extends WorkspaceProvisionerContractTest {

    @TempDir
    Path tempDir;

    private int remoteSeq;

    @Override
    protected WorkspaceProvisioner provisioner() {
        return new GitWorktreeProvisioner(tempDir.resolve("ws-root").toString());
    }

    @Override
    protected String newRemoteRepoUrl() {
        remoteSeq++;
        return GitRepoFixture.createBare(tempDir.resolve("remote-" + remoteSeq + ".git"))
                .withCommit("README.md", "hello", "init")
                .url();
    }

    @Test
    void first_provision_should_create_mirror_worktree_branch_and_base_commit() {
        GitRepoFixture fixture = GitRepoFixture.createBare(tempDir.resolve("first.git"))
                .withCommit("README.md", "hello", "init");
        WorkspaceProvisioner provisioner = provisioner();

        ProvisionedWorkspace workspace = provisioner.provision(
                new ProvisionRequest(fixture.url(), "REQ-1", "req/REQ-1", null));

        Path mirror = Path.of(workspace.getMirrorPath());
        assertThat(mirror).isDirectory();
        assertThat(Path.of(workspace.getWorktreePath())).isDirectory();
        assertThat(GitRepoFixture.tryGit(mirror, "show-ref", "--verify", "--quiet",
                "refs/heads/req/REQ-1").exitCode()).isZero();
        assertThat(workspace.getResolvedBaseCommit()).isEqualTo(fixture.headCommit());
    }

    @Test
    void fetch_prune_should_keep_local_req_branches_and_reject_mirror_semantics() {
        GitRepoFixture fixture = GitRepoFixture.createBare(tempDir.resolve("sentinel.git"))
                .withCommit("README.md", "hello", "init");
        WorkspaceProvisioner provisioner = provisioner();
        ProvisionedWorkspace first = provisioner.provision(
                new ProvisionRequest(fixture.url(), "REQ-A", "req/REQ-A", null));

        provisioner.provision(new ProvisionRequest(fixture.url(), "REQ-B", "req/REQ-B", null));

        Path mirror = Path.of(first.getMirrorPath());
        assertThat(GitRepoFixture.tryGit(mirror, "show-ref", "--verify", "--quiet",
                "refs/heads/req/REQ-A").exitCode()).isZero();
        GitRepoFixture.GitOutcome mirrorFlag = GitRepoFixture.tryGit(mirror,
                "config", "--get", "remote.origin.mirror");
        if (mirrorFlag.exitCode() == 0) {
            assertThat(mirrorFlag.output().trim()).isNotEqualTo("true");
        }
        assertThat(GitRepoFixture.git(mirror, "config", "--get", "remote.origin.fetch").trim())
                .isEqualTo("+refs/heads/*:refs/remotes/origin/*");
    }

    @Test
    void force_release_should_remove_dirty_worktree_and_keep_branch() throws Exception {
        WorkspaceProvisioner provisioner = provisioner();
        ProvisionedWorkspace workspace = provisioner.provision(
                new ProvisionRequest(newRemoteRepoUrl(), "REQ-F", "req/REQ-F", null));
        Path worktree = Path.of(workspace.getWorktreePath());
        Files.writeString(worktree.resolve("dirty.txt"), "uncommitted");

        provisioner.release(new ReleaseRequest(workspace.getMirrorPath(),
                workspace.getWorktreePath(), "req/REQ-F", true, false));

        assertThat(worktree).doesNotExist();
        assertThat(GitRepoFixture.tryGit(Path.of(workspace.getMirrorPath()),
                "show-ref", "--verify", "--quiet", "refs/heads/req/REQ-F").exitCode()).isZero();
    }

    @Test
    void release_with_remove_branch_should_delete_branch() {
        WorkspaceProvisioner provisioner = provisioner();
        ProvisionedWorkspace workspace = provisioner.provision(
                new ProvisionRequest(newRemoteRepoUrl(), "REQ-D", "req/REQ-D", null));

        provisioner.release(new ReleaseRequest(workspace.getMirrorPath(),
                workspace.getWorktreePath(), "req/REQ-D", false, true));

        assertThat(Path.of(workspace.getWorktreePath())).doesNotExist();
        assertThat(GitRepoFixture.tryGit(Path.of(workspace.getMirrorPath()),
                "show-ref", "--verify", "--quiet", "refs/heads/req/REQ-D").exitCode()).isNotZero();
    }

    @Test
    void concurrent_provision_on_same_repo_should_both_succeed() throws Exception {
        String repoUrl = newRemoteRepoUrl();
        WorkspaceProvisioner provisioner = provisioner();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ProvisionedWorkspace> firstFuture = pool.submit(() -> {
                start.await();
                return provisioner.provision(
                        new ProvisionRequest(repoUrl, "REQ-C1", "req/REQ-C1", null));
            });
            Future<ProvisionedWorkspace> secondFuture = pool.submit(() -> {
                start.await();
                return provisioner.provision(
                        new ProvisionRequest(repoUrl, "REQ-C2", "req/REQ-C2", null));
            });
            start.countDown();

            ProvisionedWorkspace first = firstFuture.get(120, TimeUnit.SECONDS);
            ProvisionedWorkspace second = secondFuture.get(120, TimeUnit.SECONDS);

            assertThat(Path.of(first.getWorktreePath())).isDirectory();
            assertThat(Path.of(second.getWorktreePath())).isDirectory();
        } finally {
            pool.shutdownNow();
        }
    }
}
