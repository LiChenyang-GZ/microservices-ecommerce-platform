package com.comp5348.bank.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Every /api/bank/* request must carry X-Service-Token; missing or wrong → 401 and the controller never runs.
 *
 * Not a @Component on purpose: Spring Boot registers every Filter bean for all paths. FilterConfig registers
 * this one for /api/* only, so /actuator/health stays open for Docker and Kubernetes health checks.
 * The token itself is never logged.
 */
public class ServiceTokenFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(ServiceTokenFilter.class);

    private final ServiceToken serviceToken;

    public ServiceTokenFilter(ServiceToken serviceToken) {
        this.serviceToken = serviceToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String presented = request.getHeader(ServiceToken.HEADER);
        if (!serviceToken.matches(presented)) {
            logger.warn("Rejected {} {}: {} service token", request.getMethod(), request.getRequestURI(),
                    presented == null ? "no" : "wrong");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"service token required\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
