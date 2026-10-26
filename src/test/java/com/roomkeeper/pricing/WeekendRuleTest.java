package com.roomkeeper.pricing;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.roomkeeper.testsupport.NightBuilder.aNight;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WeekendRuleTest {

	// A hotel night is named by its check-in day: Friday and Saturday nights are the weekend
	private final WeekendRule rule = new WeekendRule(Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
			new BigDecimal("1.15"));

	@ParameterizedTest(name = "{0} ({1}) costs {2}")
	@CsvSource({
		"2026-03-12, Thursday, 4000.00",
		"2026-03-13, Friday,   4600.00",
		"2026-03-14, Saturday, 4600.00",
		"2026-03-15, Sunday,   4000.00",
	})
	void raisesFridayAndSaturdayNights(String date, String dayName, String expected) {
		BigDecimal price = rule.apply(new BigDecimal("4000.00"), aNight().on(date).build());

		assertThat(price).isEqualByComparingTo(expected);
	}

	@Test
	void rejectsANonPositiveMultiplier() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new WeekendRule(Set.of(DayOfWeek.SATURDAY), new BigDecimal("-1")));
	}

}
