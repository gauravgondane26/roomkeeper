package com.roomkeeper.common.validation;

import java.time.ZoneId;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class TimeZoneIdValidator implements ConstraintValidator<TimeZoneId, String> {

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		return value == null || ZoneId.getAvailableZoneIds().contains(value);
	}

}
