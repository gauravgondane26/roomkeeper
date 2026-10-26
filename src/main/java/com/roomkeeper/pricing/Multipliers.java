package com.roomkeeper.pricing;

import java.math.BigDecimal;

/** Checks for the multipliers rules are built with. */
final class Multipliers {

	private Multipliers() {
	}

	static BigDecimal requirePositive(BigDecimal multiplier) {
		if (multiplier.signum() <= 0) {
			throw new IllegalArgumentException("Multiplier must be positive, was " + multiplier);
		}
		return multiplier;
	}

}
