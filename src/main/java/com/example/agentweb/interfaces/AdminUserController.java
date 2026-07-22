package com.example.agentweb.interfaces;

import com.example.agentweb.app.auth.AdminUserAppService;
import com.example.agentweb.app.auth.AdminUserView;
import com.example.agentweb.interfaces.dto.AdminUserCreateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理后台用户查询与创建接口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@RestController
@RequestMapping(path = "/api/admin-users", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminUserController {

    private final AdminUserAppService appService;

    public AdminUserController(AdminUserAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    public List<AdminUserView> list() {
        return appService.listUsers();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminUserView> create(@Valid @RequestBody AdminUserCreateRequest request) {
        AdminUserView created = appService.createUser(
                request.getUsername(), request.getPassword(), request.getRole());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
