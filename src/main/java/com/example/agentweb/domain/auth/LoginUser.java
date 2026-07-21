package com.example.agentweb.domain.auth;

import java.io.Serializable;

/**
 * 当前登录用户值对象，表达“谁登录了”：userId（隔离/审计主键）、
 * userName（展示名）、userEmail（联系方式）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-17
 */
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 1L;

    private String userId;
    private String userName;
    private String userEmail;

    public LoginUser() {
    }

    public LoginUser(String userId, String userName, String userEmail) {
        this.userId = userId;
        this.userName = userName;
        this.userEmail = userEmail;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    @Override
    public String toString() {
        return "LoginUser{userId='" + userId + "', userName='" + userName + "'}";
    }
}
