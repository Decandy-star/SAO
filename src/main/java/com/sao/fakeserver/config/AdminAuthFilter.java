package com.sao.fakeserver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 仅拦截 {@code /admin/*}：校验管理 token。
 * <p>
 * 取值优先级：Header {@code X-Admin-Token} → {@code Authorization: Bearer ...} → query {@code token=}。
 * 与游戏 TCP / 选服 QF / Version / 公告无关，不改 APK。
 * {@code sao.admin-token} 为空时拒绝所有 /admin（避免公网裸奔）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AdminAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);

    private final SaoProperties props;

    public AdminAuthFilter(SaoProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String expected = props.getAdminToken();
        if (expected == null || expected.isEmpty()) {
            log.warn("admin rejected: sao.admin-token empty path={}", request.getRequestURI());
            writeUnauthorized(response, "admin-token not configured");
            return;
        }
        String got = extractToken(request);
        if (got == null || !constantTimeEquals(expected, got)) {
            log.warn("admin rejected: bad token path={}", request.getRequestURI());
            writeUnauthorized(response, "unauthorized");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader("X-Admin-Token");
        if (header != null && !header.isEmpty()) {
            return header.trim();
        }
        String auth = request.getHeader("Authorization");
        if (auth != null) {
            String trimmed = auth.trim();
            if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return trimmed.substring(7).trim();
            }
        }
        String q = request.getParameter("token");
        if (q != null && !q.isEmpty()) {
            return q.trim();
        }
        return null;
    }

    private static void writeUnauthorized(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        byte[] body = ("{\"ok\":false,\"error\":\"" + msg + "\"}").getBytes(StandardCharsets.UTF_8);
        response.getOutputStream().write(body);
    }

    /** 避免按字符短路比较泄露长度信息（token 固定长度场景足够）。 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < x.length; i++) {
            r |= x[i] ^ y[i];
        }
        return r == 0;
    }
}
