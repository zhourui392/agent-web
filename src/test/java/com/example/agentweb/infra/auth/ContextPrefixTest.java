package com.example.agentweb.infra.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ContextPrefix} 纯函数单测:从 servlet {@code contextPath} 派生应用挂载前缀。
 *
 * <p>这是「共享域名 /qa 路径前缀」方案在后端唯一的真相源:应用经
 * {@code server.servlet.context-path: /qa} 整体挂载,网关原样透传。所有入口(域名 / 直连 IP)
 * 统一带前缀,应用据此给浏览器直跳的 URL(302 / 门户回跳)补前缀。</p>
 *
 * @author zhourui(V33215020)
 */
class ContextPrefixTest {

    private MockHttpServletRequest req(String contextPath) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (contextPath != null) {
            r.setContextPath(contextPath);
        }
        return r;
    }

    /** 根部署(contextPath=""):前缀为空,保持根路径行为。 */
    @Test
    void should_be_empty_when_context_root() {
        assertEquals("", ContextPrefix.of(req(null)));
        assertEquals("", ContextPrefix.of(req("")));
    }

    /** 标准场景:server.servlet.context-path=/qa。 */
    @Test
    void should_return_prefix_as_is() {
        assertEquals("/qa", ContextPrefix.of(req("/qa")));
    }

    /** 防御性归一:尾斜杠剔除,避免后续拼接出 //(规范容器不会出现,防实现差异)。 */
    @Test
    void should_strip_trailing_slash() {
        assertEquals("/qa", ContextPrefix.of(req("/qa/")));
    }

    /** 防御性归一:缺前导斜杠时补齐。 */
    @Test
    void should_normalize_missing_leading_slash() {
        assertEquals("/qa", ContextPrefix.of(req("qa")));
    }

    /** contextPath 为根 "/" 视同无前缀(规范要求容器给 "",防实现差异)。 */
    @Test
    void should_treat_root_slash_as_empty() {
        assertEquals("", ContextPrefix.of(req("/")));
    }

    private MockHttpServletRequest req(String contextPath, String uri) {
        MockHttpServletRequest r = req(contextPath);
        r.setRequestURI(uri);
        return r;
    }

    /** context-path 部署:requestURI 含挂载前缀,strip 剥成逻辑路径。 */
    @Test
    void strip_should_remove_context_path() {
        assertEquals("/login.html", ContextPrefix.strip(req("/qa", "/qa/login.html")));
        assertEquals("/index.html", ContextPrefix.strip(req("/qa", "/qa/index.html")));
    }

    /** 根部署:strip 原样返回 requestURI,根路径行为不变。 */
    @Test
    void strip_should_return_uri_as_is_when_no_prefix() {
        assertEquals("/login.html", ContextPrefix.strip(req(null, "/login.html")));
    }

    /** requestURI 不带前缀(理论不出现,防御):strip 原样返回。 */
    @Test
    void strip_should_return_uri_as_is_when_uri_lacks_prefix() {
        assertEquals("/login.html", ContextPrefix.strip(req("/qa", "/login.html")));
    }

    /** requestURI 恰为前缀本身(Tomcat 已 302 补斜杠,理论不出现):归一为根 "/"。 */
    @Test
    void strip_should_return_root_when_uri_equals_prefix() {
        assertEquals("/", ContextPrefix.strip(req("/qa", "/qa")));
    }

    /** 同名前缀路径(/qabc)不是 /qa 的子路径,不得误剥。 */
    @Test
    void strip_should_not_touch_lookalike_prefix() {
        assertEquals("/qabc", ContextPrefix.strip(req("/qa", "/qabc")));
    }

    /** 网关转发不规范产生 /qa//login.html:collapse 双斜杠后再剥前缀,归一到 /login.html。 */
    @Test
    void strip_should_collapse_double_slash_after_prefix() {
        assertEquals("/login.html", ContextPrefix.strip(req("/qa", "/qa//login.html")));
    }

    /** 前导双斜杠(//login.html):collapse 后归一,不再失配 PublicPaths。 */
    @Test
    void strip_should_collapse_leading_double_slash() {
        assertEquals("/login.html", ContextPrefix.strip(req("/qa", "//login.html")));
    }
}
