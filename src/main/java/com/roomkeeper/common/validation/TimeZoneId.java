package com.roomkeeper.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * A region-based time zone id such as {@code Asia/Kolkata} or {@code UTC}. Fixed offsets
 * like {@code +05:30} are rejected: they don't follow daylight saving changes. Null is
 * valid; add {@code @NotNull} to require a value.
 */
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = TimeZoneIdValidator.class)
public @interface TimeZoneId {

	String message() default "must be a time zone id such as Asia/Kolkata";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
