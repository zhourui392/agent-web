package com.example.agentweb.infra.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * 登录失败限流器，同时按直连来源 IP 和规范化用户名计数。
 *
 * <p>用户名维度阻止分布式来源持续撞同一账户，来源 IP 维度阻止单一来源轮换用户名。
 * 来源 IP 只使用 socket 远端地址，不信任客户端可伪造的转发头。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
public class LoginAttemptLimiter {

    private static final int MAX_TRACKED_SUBJECTS = 20_000;
    private static final String UNKNOWN_SOURCE = "unknown";

    private final AuthProperties properties;
    private final Clock clock;
    private final Map<String, FailureBucket> buckets = new HashMap<>();
    private long capacityBlockedUntilEpochSecond;

    @Autowired
    public LoginAttemptLimiter(AuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    private LoginAttemptLimiter(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    static LoginAttemptLimiter forTesting(AuthProperties properties, Clock clock) {
        return new LoginAttemptLimiter(properties, clock);
    }

    /**
     * 返回当前请求需要等待的秒数；零表示允许继续验证密码。
     */
    public synchronized long retryAfterSeconds(String remoteAddress, String username) {
        long now = clock.instant().getEpochSecond();
        if (capacityBlockedUntilEpochSecond > now) {
            return capacityBlockedUntilEpochSecond - now;
        }
        if (properties.getLoginMaxFailures() <= 0) {
            return windowSeconds();
        }
        long sourceRetry = retryAfter(sourceKey(remoteAddress), now);
        long accountRetry = retryAfter(accountKey(username), now);
        return Math.max(sourceRetry, accountRetry);
    }

    /** 记录一次认证失败。 */
    public synchronized void recordFailure(String remoteAddress, String username) {
        long now = clock.instant().getEpochSecond();
        increment(sourceKey(remoteAddress), now);
        increment(accountKey(username), now);
    }

    /**
     * 成功登录后清除账户维度失败记录；来源 IP 记录保留，避免用一个已知账户重置来源防线。
     */
    public synchronized void recordSuccess(String remoteAddress, String username) {
        buckets.remove(accountKey(username));
    }

    private long retryAfter(String key, long now) {
        FailureBucket bucket = buckets.get(key);
        if (bucket == null) {
            return 0L;
        }
        long elapsed = now - bucket.startedAtEpochSecond;
        if (elapsed >= windowSeconds()) {
            buckets.remove(key);
            return 0L;
        }
        if (bucket.failures < properties.getLoginMaxFailures()) {
            return 0L;
        }
        return Math.max(1L, windowSeconds() - elapsed);
    }

    private void increment(String key, long now) {
        FailureBucket bucket = buckets.get(key);
        if (bucket != null && now - bucket.startedAtEpochSecond < windowSeconds()) {
            if (bucket.failures < Integer.MAX_VALUE) {
                bucket.failures++;
            }
            return;
        }
        if (bucket != null) {
            buckets.remove(key);
        }
        ensureCapacity(now);
        if (buckets.size() >= MAX_TRACKED_SUBJECTS) {
            capacityBlockedUntilEpochSecond = now + windowSeconds();
            return;
        }
        buckets.put(key, new FailureBucket(now));
    }

    private void ensureCapacity(long now) {
        if (buckets.size() < MAX_TRACKED_SUBJECTS) {
            return;
        }
        Iterator<Map.Entry<String, FailureBucket>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            FailureBucket bucket = iterator.next().getValue();
            if (now - bucket.startedAtEpochSecond >= windowSeconds()) {
                iterator.remove();
            }
        }
    }

    private long windowSeconds() {
        return Math.max(1L, properties.getLoginFailureWindowSeconds());
    }

    private String sourceKey(String remoteAddress) {
        String normalized = remoteAddress == null || remoteAddress.trim().isEmpty()
                ? UNKNOWN_SOURCE : remoteAddress.trim();
        return "source:" + normalized;
    }

    private String accountKey(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return "account:" + normalized;
    }

    private static final class FailureBucket {
        private final long startedAtEpochSecond;
        private int failures = 1;

        private FailureBucket(long startedAtEpochSecond) {
            this.startedAtEpochSecond = startedAtEpochSecond;
        }
    }
}
