package com.roomkeeper.common.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Documents the {@code Idempotency-Key} header on an endpoint that {@link IdempotencyFilter}
 * covers. The filter enforces it; this only puts it in the API docs.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Parameter(in = ParameterIn.HEADER, name = IdempotencyFilter.HEADER, required = true,
		description = "New for each request, and the same when retrying it: a retry gets the first response back "
				+ "instead of running again. Kept for 24 hours.",
		schema = @Schema(type = "string", minLength = 1, maxLength = 255, example = "6f1c2a9e-3b7d-4e2a-9c51-0d8e7b4a2f10"))
public @interface IdempotencyKeyHeader {
}
