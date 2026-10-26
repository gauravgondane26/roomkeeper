package com.roomkeeper.pricing;

import java.math.BigDecimal;

/** The first rule: every night starts from the room type's base price. */
public class BasePriceRule implements PricingRule {

	@Override
	public BigDecimal apply(BigDecimal price, Night night) {
		return night.basePrice();
	}

}
