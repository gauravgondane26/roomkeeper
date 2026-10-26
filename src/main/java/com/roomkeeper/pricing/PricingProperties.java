package com.roomkeeper.pricing;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code roomkeeper.pricing}: the adjustments the pricing rules make. */
@ConfigurationProperties("roomkeeper.pricing")
public record PricingProperties(Occupancy occupancy, Weekend weekend, Holidays holidays) {

	/** Once booked + held reaches {@code threshold} of the rooms (0.80 is 80%), apply {@code multiplier}. */
	public record Occupancy(BigDecimal threshold, BigDecimal multiplier) {
	}

	/** Nights named by their check-in day that count as the weekend. */
	public record Weekend(Set<DayOfWeek> nights, BigDecimal multiplier) {
	}

	public record Holidays(Set<LocalDate> dates, BigDecimal multiplier) {
	}

}
