package com.roomkeeper.pricing;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code roomkeeper.pricing}: the adjustments the pricing rules make. */
@ConfigurationProperties("roomkeeper.pricing")
public record PricingProperties(Occupancy occupancy, Weekend weekend, Holidays holidays) {

	/**
	 * Bump when a pricing rule's code changes what it charges, so cheapest prices stored
	 * before the deploy are worked out again, as they are when a setting changes.
	 */
	public static final int RULES_VERSION = 1;

	/**
	 * The rules version and the settings as one line, the same however the settings are
	 * written (0.8 or 0.80, days in any order), so stored cheapest prices can be checked
	 * against what they were worked out with.
	 */
	public String fingerprint() {
		return "rules=" + RULES_VERSION
				+ ";occupancy=" + plain(occupancy.threshold()) + "x" + plain(occupancy.multiplier())
				+ ";weekend=" + sorted(weekend.nights()) + "x" + plain(weekend.multiplier())
				+ ";holidays=" + sorted(holidays.dates()) + "x" + plain(holidays.multiplier());
	}

	private static String plain(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	private static <T extends Comparable<? super T>> String sorted(Set<T> values) {
		return values.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
	}

	/** Once booked + held reaches {@code threshold} of the rooms (0.80 is 80%), apply {@code multiplier}. */
	public record Occupancy(BigDecimal threshold, BigDecimal multiplier) {
	}

	/** Nights named by their check-in day that count as the weekend. */
	public record Weekend(Set<DayOfWeek> nights, BigDecimal multiplier) {
	}

	public record Holidays(Set<LocalDate> dates, BigDecimal multiplier) {
	}

}
