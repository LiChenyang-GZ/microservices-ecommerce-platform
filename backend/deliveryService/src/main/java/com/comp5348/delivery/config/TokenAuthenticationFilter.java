package com.comp5348.delivery.config;

import com.comp5348.delivery.utils.TokenValidationClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Token 认证过滤器
 * 拦截请求并验证 token
 */
@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(TokenAuthenticationFilter.class);

    @Autowired
    private TokenValidationClient tokenValidationClient;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        // 跳过 OPTIONS 请求（CORS 预检请求，由 CorsFilter 处理）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // 跳过公开访问的端点
        String path = request.getRequestURI();
        if (path.startsWith("/api/deliveries/create") || 
            path.startsWith("/api/deliveries/webhook") ||
            // 内部调用：按订单ID取消配送（由 StoreService 调用），允许无 token
            path.startsWith("/api/deliveries/cancel-by-order") ||
            path.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // 查询和取消端点需要认证
        // 从请求头中提取 token
        String authHeader = request.getHeader("Authorization");
        String token = null;
        
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }
        
        // 如果没有 token，返回 401
        if (token == null || token.isEmpty()) {
            logger.warn("No token provided for request: {}", path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Please login first\"}");
            return;
        }
        
        // 验证 token
        TokenValidationClient.TokenValidationResult result = tokenValidationClient.validateToken(token);
        
        if (!result.isValid()) {
            logger.warn("Invalid token for request: {}, reason: {}", path, result.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"" + result.getMessage() + "\"}");
            return;
        }
        
        // 将用户信息添加到请求属性中，供 Controller 使用
        request.setAttribute("userId", result.getUserId());
        request.setAttribute("userEmail", result.getEmail());
        
        filterChain.doFilter(request, response);
    }
}
