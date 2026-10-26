package com.roomkeeper.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Runs the pricing rules in order over each night of a stay. */
public class PricingChain {

	private final List<PricingRule> rules;

	public PricingChain(List<PricingRule> rules) {
		this.rules = List.copyOf(rules);
	}

	/** One room for one night, rounded half up to cents after the last rule. */
	public BigDecimal priceOf(Night night) {
		BigDecimal price = BigDecimal.ZERO;
		for (PricingRule rule : rules) {
			price = rule.apply(price, night);
		}
		return price.setScale(2, RoundingMode.HALF_UP);
	}

	/**
	 * Prices a stay: {@code nights} must be every night of it, consecutive and in order, so
	 * a missing inventory row can't quietly make the stay cheaper.
	 */
	public PriceQuote quote(List<Night> nights, int rooms) {
		if (nights.isEmpty()) {
			throw new IllegalArgumentException("A stay needs at least one night");
		}
		if (rooms < 1) {
			throw new IllegalArgumentException("A stay needs at least one room, was " + rooms);
		}
		List<NightPrice> prices = new ArrayList<>(nights.size());
		BigDecimal perRoomTotal = BigDecimal.ZERO;
		for (int i = 0; i < nights.size(); i++) {
			Night night = nights.get(i);
			if (i > 0) {
				var expected = nights.get(i - 1).date().plusDays(1);
				if (!night.date().equals(expected)) {
					throw new IllegalArgumentException("Expected the night of " + expected + " but got " + night.date());
				}
			}
			BigDecimal price = priceOf(night);
			prices.add(new NightPrice(night.date(), price));
			perRoomTotal = perRoomTotal.add(price);
		}
		return new PriceQuote(prices, perRoomTotal, rooms, perRoomTotal.multiply(BigDecimal.valueOf(rooms)));
	}

}
