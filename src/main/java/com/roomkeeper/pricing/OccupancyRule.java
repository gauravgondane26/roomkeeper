package com.roomkeeper.pricing;

import java.math.BigDecimal;

/** Busy nights cost more: once the share of rooms booked or held reaches the threshold, apply the multiplier. */
public class OccupancyRule implements PricingRule {

	private final BigDecimal threshold;

	private final BigDecimal multiplier;

	public OccupancyRule(BigDecimal threshold, BigDecimal multiplier) {
		if (threshold.signum() <= 0 || threshold.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException("Occupancy threshold must be above 0 and at most 1, was " + threshold);
		}
		this.threshold = threshold;
		this.multiplier = Multipliers.requirePositive(multiplier);
	}

	@Override
	public BigDecimal apply(BigDecimal price, Night night) {
		if (night.totalCount() == 0) {
			return price;
		}
		// sold / total >= threshold, compared without dividing so nothing is rounded
		boolean busy = BigDecimal.valueOf(night.soldCount())
			.compareTo(threshold.multiply(BigDecimal.valueOf(night.totalCount()))) >= 0;
		return busy ? price.multiply(multiplier) : price;
	}

}
