package com.comp5348.delivery.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminOnlyInterceptor adminOnlyInterceptor;

    public WebConfig(AdminOnlyInterceptor adminOnlyInterceptor) {
        this.adminOnlyInterceptor = adminOnlyInterceptor;
    }

    /** Every handler; the interceptor itself only acts on @AdminOnly ones (auth PR 3) */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminOnlyInterceptor);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // This configuration allows all /api/** paths to be accessed
        registry.addMapping("/api/**")
                // Important: Confirm your frontend teammate's development server address and port!
                .allowedOrigins("http://localhost:3000")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}