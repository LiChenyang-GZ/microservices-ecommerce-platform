package comp5348.storeservice.config;

import comp5348.storeservice.utils.JwtUtil;
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
import java.util.ArrayList;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

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
            try {
                // Parsing checks the signature and the expiry, once (review 44 🟡8: a second parse outside this
                // try could throw for a token that expired in between, and the request got 500 instead of 401)
                email = jwtUtil.extractEmail(jwt);
            } catch (Exception e) {
                // Expired or forged: stay anonymous. SecurityConfig answers 401 on protected paths; public ones
                // still work. DEBUG, not ERROR: a browser holding an old token is normal.
                logger.debug("Ignoring an invalid JWT: " + e.getMessage());
            }
            if (email != null) {
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        email, null, new ArrayList<>());
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);

                // Also set email as request attribute for controllers to access
                request.setAttribute("userEmail", email);
            }
        }

        filterChain.doFilter(request, response);
    }
}
