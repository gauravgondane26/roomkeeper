package com.roomkeeper.pricing;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static com.roomkeeper.testsupport.NightBuilder.aNight;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OccupancyRuleTest {

	// 20% more once 80% of the rooms are booked or held
	private final OccupancyRule rule = new OccupancyRule(new BigDecimal("0.80"), new BigDecimal("1.20"));

	@Test
	void raisesThePriceOnceTheNightIsBusyEnough() {
		BigDecimal price = rule.apply(new BigDecimal("4000.00"), aNight().withSold(8, 10).build());

		assertThat(price).isEqualByComparingTo("4800.00");
	}

	@Test
	void leavesThePriceAloneBelowTheThreshold() {
		BigDecimal price = rule.apply(new BigDecimal("4000.00"), aNight().withSold(7, 10).build());

		assertThat(price).isEqualByComparingTo("4000.00");
	}

	@Test
	void comparesTheExactShareNotARoundedOne() {
		// 79 of 99 is 79.8%: just under the threshold
		BigDecimal price = rule.apply(new BigDecimal("4000.00"), aNight().withSold(79, 99).build());

		assertThat(price).isEqualByComparingTo("4000.00");
	}

	@Test
	void aNightWithNoRoomsIsLeftAlone() {
		BigDecimal price = rule.apply(new BigDecimal("4000.00"), aNight().withSold(0, 0).build());

		assertThat(price).isEqualByComparingTo("4000.00");
	}

	@Test
	void rejectsAThresholdOutsideZeroToOneOrANonPositiveMultiplier() {
		assertThatIllegalArgumentException().isThrownBy(() -> new OccupancyRule(new BigDecimal("1.5"), BigDecimal.ONE));
		assertThatIllegalArgumentException().isThrownBy(() -> new OccupancyRule(BigDecimal.ZERO, BigDecimal.ONE));
		assertThatIllegalArgumentException().isThrownBy(() -> new OccupancyRule(new BigDecimal("0.8"), BigDecimal.ZERO));
	}

}
