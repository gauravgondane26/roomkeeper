package com.roomkeeper.pricing;

import java.math.BigDecimal;

/** The manager's per-night surge factor from inventory admin: 1.50 is 50% more, 0.75 a 25% discount. */
public class SurgeRule implements PricingRule {

	@Override
	public BigDecimal apply(BigDecimal price, Night night) {
		return price.multiply(night.surgeFactor());
	}

}
