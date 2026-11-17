package com.company.incidentjmix.rest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@link com.company.incidentjmix.entity.AppUser} controller parameter
 * resolved from the X-User-Id header (ADR-002/003).
 * <p>
 * Mutating endpoints keep the default {@code optional = false}: an absent,
 * non-numeric or unknown header fails with 401 before any data change.
 * Read-only endpoints declare {@code optional = true}: an absent header is
 * allowed, a present-but-unknown one still fails with 401.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentAppUser {

    boolean optional() default false;
}
