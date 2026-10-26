package com.roomkeeper.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static com.roomkeeper.testsupport.NightBuilder.aNight;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class HolidayRuleTest {

	private final HolidayRule rule = new HolidayRule(Set.of(LocalDate.parse("2026-12-25"), LocalDate.parse("2026-12-31")),
			new BigDecimal("1.25"));

	@Test
	void raisesHolidayNights() {
		BigDecimal price = rule.apply(new BigDecimal("4000.00"), aNight().on("2026-12-25").build());

		assertThat(price).isEqualByComparingTo("5000.00");
	}

	@Test
	void leavesOtherNightsAlone() {
		BigDecimal price = rule.apply(new BigDecimal("4000.00"), aNight().on("2026-12-24").build());

		assertThat(price).isEqualByComparingTo("4000.00");
	}

	@Test
	void withNoHolidaysChangesNothing() {
		HolidayRule noHolidays = new HolidayRule(Set.of(), new BigDecimal("1.25"));

		assertThat(noHolidays.apply(new BigDecimal("4000.00"), aNight().on("2026-12-25").build()))
			.isEqualByComparingTo("4000.00");
	}

	@Test
	void rejectsANonPositiveMultiplier() {
		assertThatIllegalArgumentException().isThrownBy(() -> new HolidayRule(Set.of(), BigDecimal.ZERO));
	}

}
