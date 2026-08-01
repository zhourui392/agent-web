package com.example.agentweb.infra.workspace.document;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 稳定读取的测试观察点；生产使用固定 no-op。
 *
 * @author alex
 * @since 2026-08-01
 */
@FunctionalInterface
interface DocumentReadObserver {

    void afterRead(Path path, int attempt) throws IOException;
}
