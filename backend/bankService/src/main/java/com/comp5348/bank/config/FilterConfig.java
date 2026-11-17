package com.comp5348.bank.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {

    /** Service token on every /api/* path. /actuator is outside /api, so health checks need no token. */
    @Bean
    public FilterRegistrationBean<ServiceTokenFilter> serviceTokenFilterRegistration(ServiceToken serviceToken) {
        FilterRegistrationBean<ServiceTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ServiceTokenFilter(serviceToken));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }
}
