package com.roomkeeper.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * At most {@link #value()} bytes once encoded as UTF-8. {@code @Size} counts characters,
 * which isn't enough where the limit is in bytes: BCrypt hashes at most 72 bytes, and "é"
 * is one character but two bytes. Null is valid.
 */
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
public @interface MaxUtf8Bytes {

	int value();

	String message() default "must be at most {value} bytes (accented letters and symbols take more than one)";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
