package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Artifact 正文值对象的不可变性与 Hash 测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class ArtifactContentTest {

    @Test
    void content_should_copy_bytes_and_compute_sha256() {
        byte[] source = "requirement".getBytes(StandardCharsets.UTF_8);
        ArtifactContent content = ArtifactContent.from(source);
        source[0] = 'X';

        assertEquals(11L, content.getSizeBytes());
        assertEquals("f8dcb7a13bf4991a7d7969ea1c8add149e79b13ae91c0e6c13994da38eb3636a",
                content.getSha256());
        assertNotEquals('X', content.copyBytes()[0]);
        assertThrows(IllegalArgumentException.class, () -> ArtifactContent.from(null));
    }
}
