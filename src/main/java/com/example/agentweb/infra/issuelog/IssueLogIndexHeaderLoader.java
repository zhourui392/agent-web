package com.example.agentweb.infra.issuelog;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 加载 classpath 下 {@code issue-log-index-header.md} 模板。
 *
 * <p>提取为独立组件便于单测时注入 stub 模板,避免对真实 classpath 资源的依赖。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
@Component
public class IssueLogIndexHeaderLoader {

    private static final String DEFAULT_RESOURCE = "/issue-log-index-header.md";

    private final String resourcePath;

    public IssueLogIndexHeaderLoader() {
        this(DEFAULT_RESOURCE);
    }

    public IssueLogIndexHeaderLoader(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    public String load() {
        try (InputStream in = IssueLogIndexHeaderLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource: " + resourcePath);
            }
            byte[] bytes = readAll(in);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load index header template", e);
        }
    }

    private byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            buf.write(chunk, 0, read);
        }
        return buf.toByteArray();
    }
}
