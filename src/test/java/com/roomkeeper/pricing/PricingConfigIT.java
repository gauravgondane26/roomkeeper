package com.roomkeeper.pricing;

import java.time.DayOfWeek;
import java.time.LocalDate;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.roomkeeper.testsupport.NightBuilder.aNight;
import static org.assertj.core.api.Assertions.assertThat;

/** The pricing settings in application.yaml bind and reach the chain. */
class PricingConfigIT extends AbstractIntegrationTest {

	@Autowired
	PricingProperties properties;

	@Autowired
	PricingChain chain;

	@Test
	void bindsThePricingSettings() {
		assertThat(properties.occupancy().threshold()).isEqualByComparingTo("0.80");
		assertThat(properties.weekend().nights()).containsExactlyInAnyOrder(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
		assertThat(properties.holidays().dates()).contains(LocalDate.parse("2026-12-25"));
	}

	@Test
	void theApplicationsChainPricesAHolidayWeekendNight() {
		// Christmas 2026 is a Friday: 4000.00 x weekend 1.15 x holiday 1.25
		assertThat(chain.priceOf(aNight().on("2026-12-25").pricedAt("4000.00").build())).isEqualByComparingTo("5750.00");
	}

}
