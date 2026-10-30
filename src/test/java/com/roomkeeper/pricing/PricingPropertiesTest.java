package com.roomkeeper.pricing;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PricingPropertiesTest {

	private static final PricingProperties SETTINGS = settings("0.80", "1.20", Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
			"1.15", Set.of(LocalDate.parse("2026-12-25"), LocalDate.parse("2027-01-01")), "1.25");

	@Test
	void theFingerprintNamesTheRulesVersionAndEverySetting() {
		assertThat(SETTINGS.fingerprint()).isEqualTo("rules=" + PricingProperties.RULES_VERSION
				+ ";occupancy=0.8x1.2;weekend=FRIDAY,SATURDAYx1.15;holidays=2026-12-25,2027-01-01x1.25");
	}

	@Test
	void theSameSettingsWrittenDifferentlyHaveTheSameFingerprint() {
		var saturdayFirst = new LinkedHashSet<>(List.of(DayOfWeek.SATURDAY, DayOfWeek.FRIDAY));
		var newYearFirst = new LinkedHashSet<>(List.of(LocalDate.parse("2027-01-01"), LocalDate.parse("2026-12-25")));

		assertThat(settings("0.8", "1.2", saturdayFirst, "1.150", newYearFirst, "1.25").fingerprint())
			.isEqualTo(SETTINGS.fingerprint());
	}

	@Test
	void changingAnySettingChangesTheFingerprint() {
		var weekend = Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
		var holidays = Set.of(LocalDate.parse("2026-12-25"), LocalDate.parse("2027-01-01"));

		assertThat(List.of(settings("0.75", "1.20", weekend, "1.15", holidays, "1.25"),
				settings("0.80", "1.30", weekend, "1.15", holidays, "1.25"),
				settings("0.80", "1.20", Set.of(DayOfWeek.SATURDAY), "1.15", holidays, "1.25"),
				settings("0.80", "1.20", weekend, "1.10", holidays, "1.25"),
				settings("0.80", "1.20", weekend, "1.15", Set.of(LocalDate.parse("2026-12-25")), "1.25"),
				settings("0.80", "1.20", weekend, "1.15", holidays, "1.50")))
			.extracting(PricingProperties::fingerprint)
			.doesNotContain(SETTINGS.fingerprint())
			.doesNotHaveDuplicates();
	}

	private static PricingProperties settings(String threshold, String occupancy, Set<DayOfWeek> weekendNights,
			String weekend, Set<LocalDate> holidayDates, String holiday) {
		return new PricingProperties(new PricingProperties.Occupancy(new BigDecimal(threshold), new BigDecimal(occupancy)),
				new PricingProperties.Weekend(weekendNights, new BigDecimal(weekend)),
				new PricingProperties.Holidays(holidayDates, new BigDecimal(holiday)));
	}

}
