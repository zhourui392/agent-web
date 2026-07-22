package com.example.agentweb.app.auth;

import java.util.List;

/**
 * 管理后台用户列表读侧端口，返回安全投影而非半截聚合。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface UserAccountQueryService {

    List<AdminUserView> listAll();
}
