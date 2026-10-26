package com.roomkeeper.pricing;

import java.math.BigDecimal;

/**
 * One step in pricing a night. Rules run in order, each taking the price so far and
 * returning the adjusted price for one room. Rounding happens once, after the last rule,
 * so steps don't compound rounding errors.
 */
@FunctionalInterface
public interface PricingRule {

	BigDecimal apply(BigDecimal price, Night night);

}
