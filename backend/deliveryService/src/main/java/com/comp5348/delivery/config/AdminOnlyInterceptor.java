package com.comp5348.delivery.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces @AdminOnly (auth PR 3). Runs after routing, on the handler Spring actually chose.
 *
 * Why not a path check in TokenAuthenticationFilter: the filter sees the raw request URI, while Spring MVC routes on the
 * decoded path with ";params" removed. "/api/admin;x/dlq/delivery" and "/api/%61dmin/dlq/delivery" reached the DLQ
 * controller but did not start with "/api/admin/" (review 47 🔴1). Asking "is this handler admin-only?" has no such gap.
 *
 * The role comes from the request attribute TokenAuthenticationFilter sets from store's validate-token reply.
 * No role, or anything but ADMIN, is an ordinary user.
 */
@Component
public class AdminOnlyInterceptor implements HandlerInterceptor {

    public static final String ROLE_ATTRIBUTE = "userRole";

    private static final Logger logger = LoggerFactory.getLogger(AdminOnlyInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        boolean adminOnly = method.getBeanType().isAnnotationPresent(AdminOnly.class)
                || method.hasMethodAnnotation(AdminOnly.class);
        if (!adminOnly || "ADMIN".equals(request.getAttribute(ROLE_ATTRIBUTE))) {
            return true;
        }
        logger.warn("Non-admin user {} denied: {}", request.getAttribute("userId"), method.getMethod().getName());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"Admins only\"}");
        return false;
    }
}
