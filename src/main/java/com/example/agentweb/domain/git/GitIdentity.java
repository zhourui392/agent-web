package com.example.agentweb.domain.git;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Git 提交身份值对象：{@code name} + {@code email}，注入子进程后映射为
 * {@code GIT_AUTHOR_*}/{@code GIT_COMMITTER_*}。构造期校验非空 + 邮箱格式，
 * 不变量收口在此，禁止上层用裸字段重组规则。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public final class GitIdentity {

    /** 邮箱校验：非空 local part + @ + 含点域名，足够拦住明显非法值，不追求 RFC 5322 完备。 */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final String name;
    private final String email;

    private GitIdentity(String name, String email) {
        this.name = name;
        this.email = email;
    }

    /**
     * 工厂方法，校验并去除首尾空白。
     *
     * @param name  提交者姓名
     * @param email 提交者邮箱
     * @return 合法身份
     * @throws IllegalArgumentException name/email 为空或邮箱格式非法
     */
    public static GitIdentity of(String name, String email) {
        String trimmedName = name == null ? null : name.trim();
        String trimmedEmail = email == null ? null : email.trim();
        if (trimmedName == null || trimmedName.isEmpty()) {
            throw new IllegalArgumentException("git identity name must not be blank");
        }
        if (trimmedEmail == null || trimmedEmail.isEmpty()) {
            throw new IllegalArgumentException("git identity email must not be blank");
        }
        if (!EMAIL.matcher(trimmedEmail).matches()) {
            throw new IllegalArgumentException("invalid git identity email: " + trimmedEmail);
        }
        return new GitIdentity(trimmedName, trimmedEmail);
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GitIdentity)) {
            return false;
        }
        GitIdentity that = (GitIdentity) o;
        return name.equals(that.name) && email.equals(that.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, email);
    }

    @Override
    public String toString() {
        return "GitIdentity{name=" + name + ", email=" + email + '}';
    }
}
