package com.sao.fakeserver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 请求访问日志：打印 method + URI + 响应码 + 耗时。
 * 用于排查模拟器热更下载：客户端每个 HTTP 请求都会留一行，
 * 看是否到达假服、卡在 Version / MD5File / GameText 哪一步。
 */
@Component
public class RequestLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long t0 = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = System.currentTimeMillis() - t0;
            log.info("HTTP {} {} -> {} ({}ms)", request.getMethod(), request.getRequestURI(),
                    response.getStatus(), ms);
        }
    }
}
