package com.example.agentweb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
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
     * 裸根 {@code /} → 挂载前缀 {@code /qa/} 的重定向。挂在 Engine 级 Valve(context 路由之前生效),
     * 因为 context-path 部署下根请求落在不存在的 ROOT context,进不到 Spring,@Controller/过滤器拦不到。
     * 详见 {@link RootRedirectValve}。
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> rootRedirectCustomizer(
            @Value("${server.servlet.context-path:}") String contextPath) {
        return factory -> factory.addEngineValves(new RootRedirectValve(contextPath));
    }

    // 管理后台入口重定向(/admin、/admin/ → /admin/dashboard.html)移到 AdminEntryController:
    // 共享域名 /qa 部署下该 302 Location 由浏览器直接跟随,目标必须按 ContextPrefix 补挂载前缀
    // (sendRedirect 的相对路径不会自动加 contextPath),静态 addRedirectViewController 补不了,丢 /qa 落根域。
    // MPA 每菜单一页({@code /admin/<page>.html})仍是真实静态文件,不需路由配置。
}
