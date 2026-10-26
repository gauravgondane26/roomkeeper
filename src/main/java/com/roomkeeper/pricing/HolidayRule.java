package com.roomkeeper.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/** Holiday nights cost more. The dates come from configuration. */
public class HolidayRule implements PricingRule {

	private final Set<LocalDate> holidays;

	private final BigDecimal multiplier;

	public HolidayRule(Set<LocalDate> holidays, BigDecimal multiplier) {
		this.holidays = Set.copyOf(holidays);
		this.multiplier = Multipliers.requirePositive(multiplier);
	}

	@Override
	public BigDecimal apply(BigDecimal price, Night night) {
		return holidays.contains(night.date()) ? price.multiply(multiplier) : price;
	}

}
