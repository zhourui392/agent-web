package com.example.agentweb.interfaces;

import com.example.agentweb.app.git.GitConfigAppService;
import com.example.agentweb.app.git.GitConfigView;
import com.example.agentweb.interfaces.dto.GitConfigRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 当前用户 git 配置读写接口。凭证仅返脱敏布尔（绝不回显明文）；系统默认用户拒写由 app 层
 * 抛 {@link IllegalStateException}（→ 409）兜底，前端表单 disabled 仅作体验层。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@RestController
@RequestMapping("/api/user/git-config")
public class GitConfigController {

    private final GitConfigAppService service;

    public GitConfigController(GitConfigAppService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> get() {
        GitConfigView view = service.getForCurrentUser();
        Map<String, Object> body = new HashMap<>(16);
        body.put("name", view.getName());
        body.put("email", view.getEmail());
        body.put("credentialConfigured", view.isCredentialConfigured());
        body.put("readOnly", view.isReadOnly());
        return body;
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody GitConfigRequest request) {
        service.save(request.getName(), request.getEmail(),
                request.getCredUsername(), request.getCredPassword());
        Map<String, Object> body = new HashMap<>(16);
        body.put("success", true);
        return body;
    }
}
