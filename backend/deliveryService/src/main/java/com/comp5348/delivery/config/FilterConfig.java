package com.comp5348.delivery.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Filter configuration: two filters, two path sets, never both on one request.
 *   /api/*      → TokenAuthenticationFilter (a user's login token, frontend calls)
 *   /internal/* → ServiceTokenFilter        (X-Service-Token, store calls)
 */
@Configuration
public class FilterConfig {

    @Autowired
    private TokenAuthenticationFilter tokenAuthenticationFilter;

    @Bean
    public FilterRegistrationBean<TokenAuthenticationFilter> tokenFilterRegistration() {
        FilterRegistrationBean<TokenAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(tokenAuthenticationFilter);
        registration.addUrlPatterns("/api/*"); // Only apply filter to API paths
        registration.setOrder(1); // Set filter order
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ServiceTokenFilter> serviceTokenFilterRegistration(ServiceToken serviceToken) {
        FilterRegistrationBean<ServiceTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ServiceTokenFilter(serviceToken));
        registration.addUrlPatterns("/internal/*");
        registration.setOrder(2);
        return registration;
    }
}
