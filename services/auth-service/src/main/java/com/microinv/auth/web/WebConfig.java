package com.microinv.auth.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final RegistrationGateInterceptor registrationGate;

    public WebConfig(RegistrationGateInterceptor registrationGate) {
        this.registrationGate = registrationGate;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(registrationGate).addPathPatterns("/api/auth/register");
    }
}
