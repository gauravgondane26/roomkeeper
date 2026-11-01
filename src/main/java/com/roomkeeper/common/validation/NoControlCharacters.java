package com.roomkeeper.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;

/**
 * No control characters (line breaks, tabs, NUL and the rest of U+0000-U+001F, and DEL), for
 * names people read: they end up in log lines and email subjects, where a line break would
 * start a forged log line or an email header, and Postgres refuses NUL in text. Letters of
 * any script, symbols and emoji are fine. Null is valid.
 */
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = {})
@Pattern(regexp = "\\P{Cntrl}*")
@ReportAsSingleViolation
public @interface NoControlCharacters {

	String message() default "must not contain control characters such as line breaks";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
