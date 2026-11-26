package comp5348.storeservice.config;

import comp5348.storeservice.model.Role;
import comp5348.storeservice.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Request attribute holding the token's userId (a Long) */
    public static final String USER_ID = "userId";

    /** A JSON integer as a Long; a string, a fraction or anything else is not an id */
    private static Long wholeNumber(Object claim) {
        if (claim instanceof Integer || claim instanceof Long) {
            return ((Number) claim).longValue();
        }
        return null;
    }

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        final String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String jwt = authHeader.substring(7);
            String email = null;
            Long userId = null;
            Role role = Role.USER;
            try {
                // Parsing checks the signature and the expiry, once (review 44 🟡8: a second parse outside this
                // try could throw for a token that expired in between, and the request got 500 instead of 401)
                Claims claims = jwtUtil.parseClaims(jwt);
                email = claims.getSubject();
                userId = wholeNumber(claims.get("userId"));
                // No role claim (signed before auth PR 3) -> USER, never ADMIN
                role = Role.of(claims.get("role"));
            } catch (Exception e) {
                // Expired or forged: stay anonymous. SecurityConfig answers 401 on protected paths; public ones
                // still work. DEBUG, not ERROR: a browser holding an old token is normal.
                logger.debug("Ignoring an invalid JWT: " + e.getMessage());
            }
            // Every token store signs carries both. One without a whole-number userId did not come from
            // JwtUtil#generateToken: treat it as invalid rather than guess who it is (Obsidian 82)
            if (email != null && userId != null) {
                // ROLE_ADMIN / ROLE_USER: SecurityConfig's hasRole("ADMIN") reads this. The principal is the email, for
                // Spring and logs only: never look an account up by it (Obsidian 82, review 83 🟡3)
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        email, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);

                // The id says who the caller is. No "userEmail" attribute any more: the email in a token is a snapshot
                // from signing time, and nothing should look an account up by it (Obsidian 82)
                request.setAttribute(USER_ID, userId);
            }
        }

        filterChain.doFilter(request, response);
    }
}
