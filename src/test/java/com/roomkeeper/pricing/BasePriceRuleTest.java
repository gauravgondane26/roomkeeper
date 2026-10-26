package com.roomkeeper.pricing;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static com.roomkeeper.testsupport.NightBuilder.aNight;
import static org.assertj.core.api.Assertions.assertThat;

class BasePriceRuleTest {

	private final BasePriceRule rule = new BasePriceRule();

	@Test
	void startsFromTheRoomTypesBasePrice() {
		BigDecimal price = rule.apply(BigDecimal.ZERO, aNight().pricedAt("4500.00").build());

		assertThat(price).isEqualByComparingTo("4500.00");
	}

	@Test
	void ignoresAnyPriceBeforeIt() {
		BigDecimal price = rule.apply(new BigDecimal("999.99"), aNight().pricedAt("4500.00").build());

		assertThat(price).isEqualByComparingTo("4500.00");
	}

}
