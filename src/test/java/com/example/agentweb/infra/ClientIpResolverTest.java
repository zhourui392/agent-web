package com.example.agentweb.infra;

import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author zhourui(V33215020)
 * @since 2026/06/03
 */
public class ClientIpResolverTest {

    @Test
    public void should_use_x_forwarded_for_when_present() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9");

        assertEquals("9.9.9.9", ClientIpResolver.resolve(req));
    }

    @Test
    public void should_take_first_hop_when_x_forwarded_for_has_chain() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9, 10.0.0.1, 172.16.0.1");

        assertEquals("9.9.9.9", ClientIpResolver.resolve(req));
    }

    @Test
    public void should_trim_whitespace_around_x_forwarded_for_value() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn("  9.9.9.9  ,  10.0.0.1 ");

        assertEquals("9.9.9.9", ClientIpResolver.resolve(req));
    }

    @Test
    public void should_fall_back_to_x_real_ip_when_x_forwarded_for_blank() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn("   ");
        when(req.getHeader("X-Real-IP")).thenReturn("8.8.8.8");

        assertEquals("8.8.8.8", ClientIpResolver.resolve(req));
    }

    @Test
    public void should_fall_back_to_remote_addr_when_no_proxy_headers() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(req.getHeader("X-Real-IP")).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");

        assertEquals("127.0.0.1", ClientIpResolver.resolve(req));
    }
}
