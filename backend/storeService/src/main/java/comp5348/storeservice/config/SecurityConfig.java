package comp5348.storeservice.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Everything needs a valid user JWT except the list below (auth PR 2). No token, or a bad or expired one,
     * on any other path → 401. Which order a logged-in user may touch is checked in the services, not here.
     *
     * A bad token does not fail the request by itself (JwtAuthenticationFilter just leaves it anonymous):
     * a public page still works when the browser holds an expired token, and a protected one still gets 401.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.disable())  // Disable Spring Security CORS, use WebConfig configuration
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                // sign-up, log-in, email verification, password reset
                .requestMatchers(HttpMethod.POST, "/api/user", "/api/user/login", "/api/user/activate",
                    "/api/user/forgot-password", "/api/user/reset-password", "/api/user/check-email").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/user/check-verified/**").permitAll()
                // delivery (TokenValidationClient) and tracking (auth.go) check user tokens here without logging in
                .requestMatchers(HttpMethod.POST, "/api/user/validate-token").permitAll()
                // the catalogue
                .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                // probes
                .requestMatchers("/actuator/health/**", "/actuator/health").permitAll()
                // Spring's error page, so a 400/404 from a public endpoint is not turned into 401
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated()
            )
            // without this, an anonymous request to a protected path gets 403
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
