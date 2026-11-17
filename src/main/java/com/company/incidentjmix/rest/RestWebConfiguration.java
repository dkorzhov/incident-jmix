package com.company.incidentjmix.rest;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Registers the {@link CurrentAppUser} argument resolver (ADR-002). */
@Configuration
public class RestWebConfiguration implements WebMvcConfigurer {

    private final CurrentAppUserArgumentResolver currentAppUserArgumentResolver;

    public RestWebConfiguration(CurrentAppUserArgumentResolver currentAppUserArgumentResolver) {
        this.currentAppUserArgumentResolver = currentAppUserArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentAppUserArgumentResolver);
    }
}
