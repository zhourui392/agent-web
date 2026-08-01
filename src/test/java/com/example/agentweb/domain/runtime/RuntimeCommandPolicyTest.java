package com.example.agentweb.domain.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime 命令分类与高影响操作拒绝策略测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeCommandPolicyTest {

    private final RuntimeCommandPolicy policy = RuntimeCommandPolicy.platformDefault();

    @Test
    void blocksCommitAndPushAcrossShellWrappersOptionsAndAbsoluteExecutables() {
        assertBlocked("git commit -m reviewed", RuntimeHighImpactOperation.GIT_COMMIT);
        assertBlocked("/usr/bin/git push origin master", RuntimeHighImpactOperation.GIT_PUSH);
        assertBlocked("sh -lc 'echo ready && git -C service-a push origin master'",
                RuntimeHighImpactOperation.GIT_PUSH);
        assertBlocked("env git --git-dir=.git commit -m reviewed",
                RuntimeHighImpactOperation.GIT_COMMIT);
    }

    @Test
    void blocksDeployAndExplicitDatabaseWritesWithoutTreatingReadsAsAuthorization() {
        assertBlocked("kubectl apply -f deployment.yml",
                RuntimeHighImpactOperation.LOCAL_DEPLOY);
        assertBlocked("helm upgrade agent-web ./chart",
                RuntimeHighImpactOperation.LOCAL_DEPLOY);
        assertBlocked("./scripts/deploy.sh local",
                RuntimeHighImpactOperation.LOCAL_DEPLOY);
        assertBlocked("psql service -c 'update account set enabled=true'",
                RuntimeHighImpactOperation.PRODUCTION_WRITE);

        RuntimeCommandAssessment read = policy.assess("git status --short");
        assertFalse(read.isBlocked());
        assertEquals(RuntimeCommandClass.GIT, read.getCommandClass());
        RuntimeCommandAssessment query = policy.assess(
                "psql service -c 'select count(*) from account'");
        assertFalse(query.isBlocked());
    }

    @Test
    void classifiesTestBuildAndGeneralCommandsWithoutLeakingCommandText() {
        assertEquals(RuntimeCommandClass.TEST,
                policy.assess("./mvnw -q test").getCommandClass());
        assertEquals(RuntimeCommandClass.TEST,
                policy.assess("npm run test -- --run").getCommandClass());
        assertEquals(RuntimeCommandClass.BUILD,
                policy.assess("npm run build").getCommandClass());
        assertEquals(RuntimeCommandClass.SHELL,
                policy.assess("rg --files src").getCommandClass());
    }

    @Test
    void exposesOnlyTypedForbiddenPrefixesForProviderPolicyMaterialization() {
        List<RuntimeCommandPrefix> prefixes = policy.getForbiddenPrefixes();

        assertTrue(prefixes.stream().anyMatch(prefix ->
                prefix.getOperation() == RuntimeHighImpactOperation.GIT_PUSH
                        && prefix.getTokens().equals(
                        java.util.Arrays.asList("git", "push"))));
        assertTrue(prefixes.stream().allMatch(prefix ->
                !prefix.getTokens().isEmpty() && prefix.getOperation() != null));
        assertFalse(prefixes.toString().contains("password"));
    }

    private void assertBlocked(String command, RuntimeHighImpactOperation operation) {
        RuntimeCommandAssessment assessment = policy.assess(command);
        assertTrue(assessment.isBlocked(), command);
        assertEquals(operation, assessment.blockedOperation().get());
    }
}
