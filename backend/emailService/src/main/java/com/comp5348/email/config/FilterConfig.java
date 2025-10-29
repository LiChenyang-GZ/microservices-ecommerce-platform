package com.comp5348.email.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {

    @Autowired
    private TokenAuthenticationFilter tokenAuthenticationFilter;

    @Bean
    public FilterRegistrationBean<TokenAuthenticationFilter> tokenFilterRegistration() {
        FilterRegistrationBean<TokenAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(tokenAuthenticationFilter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }
}
