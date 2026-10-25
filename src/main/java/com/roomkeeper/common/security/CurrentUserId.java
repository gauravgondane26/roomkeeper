package com.roomkeeper.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * Injects the caller's user id (the access token's subject) into a controller parameter:
 * {@code get(@CurrentUserId long userId)}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AuthenticationPrincipal(expression = "T(java.lang.Long).valueOf(subject)")
@Parameter(hidden = true) // comes from the token, not the request, so keep it out of the API docs
public @interface CurrentUserId {
}
