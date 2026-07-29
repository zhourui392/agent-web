package com.example.agentweb.app.chatrun;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CodexCommandToolNameResolverTest {
    private final CodexCommandToolNameResolver resolver = new CodexCommandToolNameResolver(new ObjectMapper());

    @Test
    void resolveCommand_shouldSkipShellAndWorkingDirectoryPrefix() {
        assertEquals("rg", resolver.resolveCommand("/bin/bash -lc 'cd /workspace && rg -n tool src'"));
        assertEquals("git", resolver.resolveCommand("bash -c \"git status --short\""));
    }

    @Test
    void resolveCommand_shouldSkipEnvironmentAndExecutablePath() {
        assertEquals("python", resolver.resolveCommand("JAVA_HOME=/opt/java env DEBUG=1 /usr/bin/python app.py"));
        assertEquals("npm", resolver.resolveCommand("sudo /usr/bin/npm test"));
    }

    @Test
    void resolveCommand_shouldKeepShellNameForComplexScript() {
        assertEquals("bash", resolver.resolveCommand(
                "/bin/bash -lc \"for c in a b; do git -C qpon-gateway-flashsale show -s \\\"$c\\\"; done\""));
        assertEquals("sh", resolver.resolveCommand("sh -c 'if test -f pom.xml; then mvn test; fi'"));
        assertEquals("bash", resolver.resolveCommand("bash -lc 'result=$(git status); printf %s result'"));
    }

    @Test
    void resolveInputJson_shouldReadHistoricalCommand() {
        assertEquals("mvn", resolver.resolveInputJson("{\"command\":\"/bin/bash -lc 'mvn test'\"}"));
        assertNull(resolver.resolveInputJson("{\"other\":true}"));
    }
}
