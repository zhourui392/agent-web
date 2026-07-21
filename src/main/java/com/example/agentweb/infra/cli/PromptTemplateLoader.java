package com.example.agentweb.infra.cli;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 从 classpath 加载 prompt 模板,统一 issue-log 子域内三处各自实现的"剥
 * {@code classpath:} 前缀 → {@link Class#getResourceAsStream} → 兜底"流程。
 *
 * <p>调用方在构造期一次性加载,把结果缓存为 final 字段。加载失败时只 warn 落日志,
 * 回退到调用方提供的内联默认模板——保证 bean 永远可构造、按钮永远可用。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-24
 */
@Slf4j
public final class PromptTemplateLoader {

    private static final String CLASSPATH_PREFIX = "classpath:";

    private PromptTemplateLoader() {
    }

    /**
     * 解析 {@code configuredPath} 与 {@code defaultResource},读出模板文本。
     *
     * <p>解析规则:</p>
     * <ul>
     *   <li>{@code configuredPath} 以 {@code classpath:} 开头 → 剥前缀作为资源路径</li>
     *   <li>{@code configuredPath} 为空 / 不带 {@code classpath:} 前缀 → 使用 {@code defaultResource}</li>
     * </ul>
     *
     * <p>失败处理(资源不存在 / IO 异常)统一回退到 {@code inlineFallback} 并 warn 落日志,
     * 永不向上抛异常。</p>
     *
     * @param configuredPath  调用方配置的模板路径(允许 {@code null})
     * @param defaultResource configuredPath 缺省时使用的 classpath 资源路径
     * @param inlineFallback  资源加载失败时的内联兜底文本
     * @return 模板文本,UTF-8 编码
     */
    public static String load(String configuredPath, String defaultResource, String inlineFallback) {
        String resourcePath = (configuredPath != null && configuredPath.startsWith(CLASSPATH_PREFIX))
                ? configuredPath.substring(CLASSPATH_PREFIX.length())
                : defaultResource;
        try (InputStream in = PromptTemplateLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                log.warn("prompt template missing at {} — falling back to inline default", resourcePath);
                return inlineFallback;
            }
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("failed to load prompt template at {}, using inline fallback", resourcePath, e);
            return inlineFallback;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            buf.write(chunk, 0, read);
        }
        return buf.toByteArray();
    }
}
