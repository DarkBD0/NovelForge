package com.novelforge.infrastructure;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalAccessFilter extends OncePerRequestFilter {
    private static final Set<String> HOSTS=Set.of("127.0.0.1", "localhost", "[::1]", "::1");
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        if (req.getRequestURI().startsWith("/api/")) res.setHeader("Cache-Control", "no-store");
        if (!HOSTS.contains(req.getServerName()) || !Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(req.getRemoteAddr())) {
            reject(res, 403, "仅允许本机访问"); return;
        }
        String origin=req.getHeader("Origin");
        if (origin != null) {
            try {
                URI uri=URI.create(origin);
                int port=uri.getPort()==-1 ? (uri.getScheme().equals("https")?443:80) : uri.getPort();
                if (!req.getScheme().equals(uri.getScheme()) || !req.getServerName().equals(uri.getHost()) || req.getServerPort()!=port) {
                    reject(res, 403, "拒绝跨来源请求"); return;
                }
            } catch (Exception e) { reject(res, 403, "来源不合法"); return; }
        }
        if (req.getHeader("Sec-Fetch-Site") != null && req.getHeader("Sec-Fetch-Site").equals("cross-site")) { reject(res,403,"拒绝跨站请求"); return; }
        if (req.getRequestURI().startsWith("/api/") && !Set.of("GET", "HEAD").contains(req.getMethod())) {
            if (!"1".equals(req.getHeader("X-NovelForge-Request"))) { reject(res,403,"写入请求缺少本机应用标识"); return; }
            if (req.getContentLengthLong()>1_500_000) { reject(res,413,"请求内容过大"); return; }
            if (req.getContentLengthLong()>0 && (req.getContentType()==null || !req.getContentType().startsWith("application/json"))) { reject(res,415,"请使用 JSON 请求"); return; }
        }
        chain.doFilter(req,res);
    }
    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8"); response.getWriter().write("{\"message\":\""+message+"\"}");
    }
}
