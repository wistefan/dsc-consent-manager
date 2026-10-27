package com.seamware.consentmanager.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Serves a controller's routes at the origin root, outside {@code consent-manager.api-prefix}.
 * Filters and the security rules still see the route under its full path, as they see every other.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface OriginRoot {}
