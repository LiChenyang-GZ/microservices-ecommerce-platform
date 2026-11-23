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
 * An allow-list, not a block-list: on /api/* nothing is public any more (rate limits, Obsidian 54: the
 * registration page asks store for a code, and store calls send-verification with its token). Every path needs
 * X-Service-Token, whether or not an endpoint exists there.
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
     * Empty. verify-code was public until A11 (store's activate checks the code now); send-verification until the
     * rate limits (Obsidian 54): the browser called it directly, and email, which knows no accounts, would mail any
     * address any number of times. Now store counts the request, checks the account and calls it with its token.
     * Kept as a set so the rule stays visible: anything added here is reachable by anyone.
     */
    static final Set<String> PUBLIC_PATHS = Set.of();

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
