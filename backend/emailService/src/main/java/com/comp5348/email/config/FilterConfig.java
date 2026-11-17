package com.comp5348.email.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {

    /** Service token on /api/* except the public registration endpoints (ServiceTokenFilter#PUBLIC_PATHS). */
    @Bean
    public FilterRegistrationBean<ServiceTokenFilter> serviceTokenFilterRegistration(ServiceToken serviceToken) {
        FilterRegistrationBean<ServiceTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ServiceTokenFilter(serviceToken));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }
}
