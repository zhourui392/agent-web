package com.example.agentweb.domain.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 交付策略（纯域规则）：push ref 白名单、commit trailer 组装、MR 强制草稿。
 *
 * <p>与 infra 侧 GitWorktreeProvisioner 的禁 --mirror 红线互为双保险：
 * 即使网关实现被改坏，这里也只放行 req/* 单分支显式推送。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public final class DeliveryPolicy {

    private static final String REQ_NAMESPACE = "req/";

    /** req/ 之后的每一段只允许安全字符,且段不得以 - 开头(防被当 git 选项)、不得为 .. */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._][A-Za-z0-9._-]*");

    /**
     * 只允许推 req/* 命名空间的单分支,违规抛 {@link PushRefForbiddenException}。
     *
     * @param branch 待推送分支名(非 refspec)
     */
    public void assertPushRefAllowed(String branch) {
        if (branch == null || branch.isBlank() || !branch.startsWith(REQ_NAMESPACE)) {
            throw new PushRefForbiddenException(branch);
        }
        String remainder = branch.substring(REQ_NAMESPACE.length());
        if (remainder.isEmpty()) {
            throw new PushRefForbiddenException(branch);
        }
        for (String segment : remainder.split("/", -1)) {
            if (segment.contains("..") || !SAFE_SEGMENT.matcher(segment).matches()) {
                throw new PushRefForbiddenException(branch);
            }
        }
    }

    /**
     * commit trailer 组装:Agent-Web-Session 回链必带;默认账号操作时追加 Operated-By 实际操作人。
     *
     * @param sessionUrl          平台会话回链,必填
     * @param actorUserId         实际操作人,默认账号时必填
     * @param usingDefaultAccount 是否使用系统默认 GitLab 账号
     * @return trailer 行列表(顺序固定)
     */
    public List<String> buildCommitTrailers(String sessionUrl, String actorUserId,
                                            boolean usingDefaultAccount) {
        if (sessionUrl == null || sessionUrl.isBlank()) {
            throw new IllegalArgumentException("sessionUrl 必填");
        }
        List<String> trailers = new ArrayList<>();
        trailers.add("Agent-Web-Session: " + sessionUrl);
        if (usingDefaultAccount) {
            if (actorUserId == null || actorUserId.isBlank()) {
                throw new IllegalArgumentException("默认账号操作必须记录实际操作人");
            }
            trailers.add("Operated-By: " + actorUserId);
        }
        return List.copyOf(trailers);
    }

    /**
     * MR 一律草稿创建:标题无 draft 前缀则补 "Draft: ",已带(大小写不敏感)不叠加。
     *
     * @param title 原始 MR 标题
     * @return 保证带 draft 前缀的标题
     */
    public String draftTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("MR 标题必填");
        }
        if (title.toLowerCase(Locale.ROOT).startsWith("draft:")) {
            return title;
        }
        return "Draft: " + title;
    }
}
