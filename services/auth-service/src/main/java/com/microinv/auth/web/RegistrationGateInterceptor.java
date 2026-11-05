package com.microinv.auth.web;

import com.microinv.auth.common.ApiException;
import com.microinv.auth.common.ErrorCode;
import com.microinv.auth.config.RegistrationProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Con REGISTRATION_ENABLED=false el registro responde 403 REGISTRATION_DISABLED antes de leer o
 * validar el cuerpo (no se revela nada sobre usuarios ni reglas).
 */
@Component
public class RegistrationGateInterceptor implements HandlerInterceptor {

    private final RegistrationProperties properties;

    public RegistrationGateInterceptor(RegistrationProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.enabled()) {
            throw new ApiException(ErrorCode.REGISTRATION_DISABLED);
        }
        return true;
    }
}
