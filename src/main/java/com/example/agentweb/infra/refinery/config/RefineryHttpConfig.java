package com.example.agentweb.infra.refinery.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.Proxy;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * refinery 模块独占的 RestTemplate, 走 Ark 公网, 强制 NO_PROXY 防止本地 SOCKS 代理污染 TLS.
 *
 * <p>仅在 {@code agent.refinery.enabled=true} 时注册, 关闭态下不创建 Bean.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
@Configuration
@ConditionalOnProperty(prefix = "agent.refinery", name = "enabled", havingValue = "true")
public class RefineryHttpConfig {

    @Bean(name = "chatRagRestTemplate")
    public RestTemplate chatRagRestTemplate(RefineryProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setProxy(Proxy.NO_PROXY);
        factory.setConnectTimeout(props.getEmbedding().getHttpConnectTimeoutMs());
        factory.setReadTimeout(props.getEmbedding().getHttpReadTimeoutMs());
        return new RestTemplate(factory);
    }

    @Bean(name = "chatRagClock")
    public Clock chatRagClock() {
        return Clock.systemUTC();
    }

    /**
     * "清空并重跑"管理操作的后台执行器: 单线程串行, 避免一次拉起大量 CLI 子进程,
     * 与 scheduler 的串行 ingest 语义一致. 关闭态不创建 Bean。
     */
    @Bean(name = "chatRagRebuildExecutor", destroyMethod = "shutdown")
    public ExecutorService chatRagRebuildExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "refinery-rebuild-" + seq.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newSingleThreadExecutor(factory);
    }
}
