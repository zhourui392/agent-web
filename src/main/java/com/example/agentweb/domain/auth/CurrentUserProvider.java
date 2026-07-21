package com.example.agentweb.domain.auth;

/**
 * 「当前登录用户」领域服务，供会话数据隔离使用。仅依赖 {@link UserContext} 端口，不触碰 infra。
 *
 * <p>三态语义（{@link #shouldFilter()} 据此分流）：</p>
 * <ul>
 *   <li>普通用户：隔离开关开 + {@link #currentUserId()} 非空 → 需按 user_id 过滤。</li>
 *   <li>null：后台线程(refinery tick/rebuild)、SSE forked 线程、无登录上下文的入口
 *       拿不到当前用户 → 不过滤，看全部(bypass)。绝不能据此加 {@code user_id = null} 过滤，
 *       否则后台 findById 会把有主会话误判为不存在。</li>
 *   <li>隔离总开关关闭（{@code agent.chat.user-isolation-enabled=false}）→ 一律不过滤，
 *       全员互见，即便有明确当前用户。代码默认 {@code true}(安全)，由配置/环境变量放开。</li>
 * </ul>
 *
 * <p>管理后台的跨用户视野走独立的 {@code /admin/*} 路径（{@code AdminAuthFilter} 口令鉴权 +
 * {@code ConversationQueryService} 全量投影），不再依赖"主路径上某些用户越权"。</p>
 *
 * @author zhourui(V33215020)
 */
public class CurrentUserProvider {

    private final UserContext userContext;

    /** 对话用户隔离总开关；false 时全员互见。默认 true(安全)。 */
    private final boolean isolationEnabled;

    /** 默认构造：隔离开关开(true)，供单测/无配置场景使用。 */
    public CurrentUserProvider(UserContext userContext) {
        this(userContext, true);
    }

    public CurrentUserProvider(UserContext userContext, boolean isolationEnabled) {
        this.userContext = userContext;
        this.isolationEnabled = isolationEnabled;
    }

    /**
     * 当前登录用户标识；拿不到（非请求线程、未登录、无会话上下文）一律返回 {@code null}。
     */
    public String currentUserId() {
        return userContext.currentUserId();
    }

    /**
     * 当前登录用户姓名；拿不到一律返回 {@code null}。
     */
    public String currentUserName() {
        return userContext.currentUserName();
    }

    /**
     * 是否需要对会话查询做 user_id 隔离过滤。
     * 仅「隔离开关开 且 有明确当前用户」时为 true；开关关闭 → 全员互见。
     */
    public boolean shouldFilter() {
        return isolationEnabled && currentUserId() != null;
    }
}
