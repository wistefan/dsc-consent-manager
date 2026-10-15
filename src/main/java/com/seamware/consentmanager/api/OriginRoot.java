package com.seamware.consentmanager.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Serves a controller's routes at the origin root, outside {@code micronaut.server.context-path}.
 * Filters bound to {@code /**}, the security filter among them, are context-path-relative and never
 * see such a route, so only a public route that needs none of them may carry it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface OriginRoot {}
