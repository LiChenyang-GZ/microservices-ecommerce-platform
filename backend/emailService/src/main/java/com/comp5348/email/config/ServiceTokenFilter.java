package com.comp5348.email.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * An allow-list, not a block-list: on /api/* only the registration page's send-verification is public;
 * every other path needs X-Service-Token, whether or not an endpoint exists there.
 * So a new endpoint added later is closed by default, and a removed one answers 401 to strangers
 * (they learn nothing) and 404 only to store.
 *
 * Exact path match, not startsWith: "/api/email/send-verification-anything" must not slip through.
 * Not a @Component: Spring Boot would register a Filter bean for every path. FilterConfig registers it
 * for /api/* only, so /actuator/health stays open. The token itself is never logged.
 */
public class ServiceTokenFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(ServiceTokenFilter.class);

    /**
     * Called by the browser on the registration page (frontend services/api.js), so no service token.
     * verify-code used to be here too; since A11 the browser hands the code to store's activate and store asks
     * email with its service token, so a stranger cannot try codes against email directly.
     */
    static final Set<String> PUBLIC_PATHS = Set.of("/api/email/send-verification");

    private final ServiceToken serviceToken;

    public ServiceTokenFilter(ServiceToken serviceToken) {
        this.serviceToken = serviceToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (PUBLIC_PATHS.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        String presented = request.getHeader(ServiceToken.HEADER);
        if (!serviceToken.matches(presented)) {
            logger.warn("Rejected {} {}: {} service token", request.getMethod(), path,
                    presented == null ? "no" : "wrong");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"service token required\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
