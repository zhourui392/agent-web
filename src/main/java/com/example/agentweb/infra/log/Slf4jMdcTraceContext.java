package com.example.agentweb.infra.log;

import com.example.agentweb.app.logging.TraceContext;
import org.springframework.stereotype.Component;

/**
 * SLF4J MDC 追踪上下文适配器。
 *
 * @author alex
 * @since 2026-07-23
 */
@Component
public class Slf4jMdcTraceContext implements TraceContext {

    @Override
    public String newTraceIdIfAbsent() {
        return MdcContext.newTraceIdIfAbsent();
    }

    @Override
    public void clear() {
        MdcContext.clear();
    }
}
