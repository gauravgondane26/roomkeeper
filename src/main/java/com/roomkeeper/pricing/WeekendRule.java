package com.roomkeeper.pricing;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.Set;

/**
 * Weekend nights cost more. A night is named by its check-in day, so the weekend is
 * usually Friday and Saturday nights.
 */
public class WeekendRule implements PricingRule {

	private final Set<DayOfWeek> weekendNights;

	private final BigDecimal multiplier;

	public WeekendRule(Set<DayOfWeek> weekendNights, BigDecimal multiplier) {
		this.weekendNights = Set.copyOf(weekendNights);
		this.multiplier = Multipliers.requirePositive(multiplier);
	}

	@Override
	public BigDecimal apply(BigDecimal price, Night night) {
		return weekendNights.contains(night.date().getDayOfWeek()) ? price.multiply(multiplier) : price;
	}

}
