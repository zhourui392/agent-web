package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeState;
import com.example.agentweb.app.runtime.port.RuntimeTerminationReason;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * executionId、稳定 Runtime Handle 与真实 Process 的进程内注册契约。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeProcessRegistryTest {

    @Test
    void retainsStableObservationWhileReleasingRealProcessAfterTermination() {
        RuntimeProcessRegistry registry = new RuntimeProcessRegistry();
        Process process = new StubProcess();

        RuntimeHandle handle = registry.register("exec-registry", process);
        registry.addOutputBytes(handle, 17L);

        assertSame(process, registry.process(handle).orElseThrow(AssertionError::new));
        assertEquals(RuntimeState.RUNNING, registry.observe(handle).getState());
        assertEquals(17L, registry.observe(handle).getObservedOutputBytes());

        registry.markStopRequested(handle);
        assertEquals(RuntimeState.STOP_REQUESTED, registry.observe(handle).getState());
        registry.markTerminated(handle, 143, RuntimeTerminationReason.REQUESTED_STOP);
        registry.releaseProcess(handle);

        assertEquals(RuntimeState.TERMINATED, registry.observe(handle).getState());
        assertEquals(RuntimeTerminationReason.REQUESTED_STOP,
                registry.observe(handle).termination().orElseThrow(AssertionError::new).getReason());
        assertFalse(registry.process(handle).isPresent());
        assertTrue(registry.activeHandles().isEmpty());
    }

    @Test
    void rejectsDuplicateExecutionIdAndReturnsNotFoundForForeignHandle() {
        RuntimeProcessRegistry registry = new RuntimeProcessRegistry();
        RuntimeHandle handle = registry.register("exec-duplicate", new StubProcess());

        assertThrows(IllegalStateException.class,
                () -> registry.register("exec-duplicate", new StubProcess()));

        RuntimeHandle foreign = new RuntimeHandle("foreign", "runtime:foreign");
        assertEquals(RuntimeState.NOT_FOUND, registry.observe(foreign).getState());
        assertFalse(registry.process(foreign).isPresent());
        assertTrue(handle.getHandleId().startsWith("runtime:")
                || handle.getHandleId().startsWith("pid:"));
    }

    private static final class StubProcess extends Process {

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
        }
    }
}
