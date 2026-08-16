package com.example.agentweb.support;

import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Path;

/**
 * 返回真实路径的 {@link TempDir}。
 *
 * <p>macOS 上 JUnit 默认临时目录位于 {@code /var}（符号链接到 {@code /private/var}），
 * 经过 fail-closed 真实路径校验的组件（Runtime workspace、能力目录、附件存储等）会拒绝
 * 该路径，导致测试在本机开发机上大面积误败。用法与 {@code @TempDir} 一致。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-08-16
 */
@Target({ElementType.ANNOTATION_TYPE, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@TempDir(factory = RealTempDir.RealPathFactory.class)
public @interface RealTempDir {

    /** 委托标准工厂创建目录后解析为真实路径；递归清理由 TempDirectory 扩展负责。 */
    final class RealPathFactory implements TempDirFactory {

        @Override
        public Path createTempDirectory(AnnotatedElementContext elementContext,
                                        ExtensionContext extensionContext) throws Exception {
            return TempDirFactory.Standard.INSTANCE
                    .createTempDirectory(elementContext, extensionContext).toRealPath();
        }
    }
}
