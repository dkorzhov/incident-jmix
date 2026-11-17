package com.company.incidentjmix.rest;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.service.AppUserService;
import io.jmix.core.security.SystemAuthenticator;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link CurrentAppUser} parameters from the X-User-Id header
 * (ADR-002). Validation against the preloaded user list is delegated to
 * {@link AppUserService#resolveActor}; UnknownUserException propagates to
 * {@link RestExceptionHandler} and becomes 401 before the controller runs.
 * <p>
 * The lookup runs with system authentication: /api/** requests are anonymous
 * at the Spring Security level and carry no Jmix entity permissions.
 */
@Component
public class CurrentAppUserArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String USER_ID_HEADER = "X-User-Id";

    private final AppUserService appUserService;
    private final SystemAuthenticator systemAuthenticator;

    public CurrentAppUserArgumentResolver(AppUserService appUserService, SystemAuthenticator systemAuthenticator) {
        this.appUserService = appUserService;
        this.systemAuthenticator = systemAuthenticator;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(AppUser.class)
                && parameter.hasParameterAnnotation(CurrentAppUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        CurrentAppUser annotation = parameter.getParameterAnnotation(CurrentAppUser.class);
        boolean required = annotation == null || !annotation.optional();
        String headerValue = webRequest.getHeader(USER_ID_HEADER);
        return systemAuthenticator.withSystem(() -> appUserService.resolveActor(headerValue, required));
    }
}
