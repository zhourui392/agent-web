package com.example.agentweb.infra.workspace.document;

import java.nio.file.Path;

/**
 * 每次稳定读取 attempt 都重新执行的 Scoped Path 解析源。
 *
 * @author alex
 * @since 2026-08-01
 */
@FunctionalInterface
interface DocumentPathSource {

    Path resolve();
}
