package com.dmg.notify.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final ActiveTenantInterceptor activeTenant;

    public WebConfig(ActiveTenantInterceptor activeTenant) { this.activeTenant = activeTenant; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(activeTenant).addPathPatterns("/api/v1/**");
    }
}
