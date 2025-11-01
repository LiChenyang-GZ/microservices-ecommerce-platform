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
 * Token Authentication Filter
 * Intercept requests and validate token
 */
@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(TokenAuthenticationFilter.class);

    @Autowired
    private TokenValidationClient tokenValidationClient;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        // Skip OPTIONS requests (CORS preflight requests, handled by CorsFilter)
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // Skip publicly accessible endpoints
        String path = request.getRequestURI();
        if (path.startsWith("/api/deliveries/create") || 
            path.startsWith("/api/deliveries/webhook") ||
            // Internal call: cancel delivery by order ID (called by StoreService), allow without token
            path.startsWith("/api/deliveries/cancel-by-order") ||
            path.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // Query and cancel endpoints require authentication
        // Extract token from request header
        String authHeader = request.getHeader("Authorization");
        String token = null;
        
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }
        
        // If no token, return 401
        if (token == null || token.isEmpty()) {
            logger.warn("No token provided for request: {}", path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Please login first\"}");
            return;
        }
        
        // Validate token
        TokenValidationClient.TokenValidationResult result = tokenValidationClient.validateToken(token);
        
        if (!result.isValid()) {
            logger.warn("Invalid token for request: {}, reason: {}", path, result.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"" + result.getMessage() + "\"}");
            return;
        }
        
        // Add user information to request attributes for Controller use
        request.setAttribute("userId", result.getUserId());
        request.setAttribute("userEmail", result.getEmail());
        
        filterChain.doFilter(request, response);
    }
}
