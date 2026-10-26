package com.roomkeeper.pricing;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static com.roomkeeper.testsupport.NightBuilder.aNight;
import static org.assertj.core.api.Assertions.assertThat;

class SurgeRuleTest {

	private final SurgeRule rule = new SurgeRule();

	@Test
	void multipliesByTheNightsSurgeFactor() {
		BigDecimal price = rule.apply(new BigDecimal("4000.00"), aNight().withSurge("1.50").build());

		assertThat(price).isEqualByComparingTo("6000.00");
	}

	@Test
	void aSurgeOfOneChangesNothing() {
		BigDecimal price = rule.apply(new BigDecimal("4000.00"), aNight().withSurge("1.00").build());

		assertThat(price).isEqualByComparingTo("4000.00");
	}

	@Test
	void aSurgeBelowOneIsADiscount() {
		BigDecimal price = rule.apply(new BigDecimal("4000.00"), aNight().withSurge("0.75").build());

		assertThat(price).isEqualByComparingTo("3000.00");
	}

}
