package com.comp5348.bank.config;

import com.comp5348.bank.utils.TokenValidationClient;
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

@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(TokenAuthenticationFilter.class);

    @Autowired
    private TokenValidationClient tokenValidationClient;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String path = request.getRequestURI();
        // 跳过不需要认证的端点
        if (path.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        String authHeader = request.getHeader("Authorization");
        String token = null;
        
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }
        
        if (token == null || token.isEmpty()) {
            logger.warn("No token provided for request: {}", path);
            filterChain.doFilter(request, response);
            return;
        }
        
        TokenValidationClient.TokenValidationResult result = tokenValidationClient.validateToken(token);
        
        if (!result.isValid()) {
            logger.warn("Invalid token for request: {}, reason: {}", path, result.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"" + result.getMessage() + "\"}");
            return;
        }
        
        request.setAttribute("userId", result.getUserId());
        request.setAttribute("userEmail", result.getEmail());
        
        filterChain.doFilter(request, response);
    }
}
