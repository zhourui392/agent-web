package com.example.agentweb.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @author zhourui(V33215020)
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // 120 minutes – matches the user's expected max session duration
        configurer.setDefaultTimeout(120L * 60L * 1000L);
    }

    /**
     * 管理后台入口重定向:{@code /admin}、{@code /admin/} → {@code /admin/dashboard.html}。
     *
     * <p>应用挂在域名根路径, 302 Location 直接用根相对路径即可。
     * (曾因 {@code /qa} 挂载部署需要运行时补 contextPath 而放在 Controller 里,挂载前缀废弃后退回静态配置。)
     * MPA 每菜单一页({@code /admin/<page>.html})是真实静态文件,不需路由配置。</p>
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/admin", "/admin/dashboard.html");
        registry.addRedirectViewController("/admin/", "/admin/dashboard.html");
    }
}
