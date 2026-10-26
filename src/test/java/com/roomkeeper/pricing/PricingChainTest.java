package com.roomkeeper.pricing;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static com.roomkeeper.testsupport.NightBuilder.aNight;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PricingChainTest {

	private final PricingChain chain = new PricingChain(List.of(new BasePriceRule(), new SurgeRule(),
			new WeekendRule(Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), new BigDecimal("1.15"))));

	@Test
	void appliesEveryRuleInOrder() {
		// Friday with a 10% surge: 4000.00 x 1.10 x 1.15
		BigDecimal price = chain.priceOf(aNight().on("2026-03-13").pricedAt("4000.00").withSurge("1.10").build());

		assertThat(price).isEqualTo(new BigDecimal("5060.00"));
	}

	@Test
	void roundsHalfUpToCentsOnceAtTheEnd() {
		// 0.05 x 0.5 = 0.025, which rounds half up to 0.03 (banker's rounding would give 0.02)
		BigDecimal price = chain.priceOf(aNight().pricedAt("0.05").withSurge("0.5").build());

		assertThat(price).isEqualTo(new BigDecimal("0.03"));
	}

	@Test
	void pricesEveryNightOfAStayAndTotalsIt() {
		// Thursday, Friday, Saturday
		PriceQuote quote = chain.quote(List.of(aNight().on("2026-03-12").build(), aNight().on("2026-03-13").build(),
				aNight().on("2026-03-14").build()), 2);

		assertThat(quote.nights()).extracting(NightPrice::date)
			.containsExactly(LocalDate.parse("2026-03-12"), LocalDate.parse("2026-03-13"), LocalDate.parse("2026-03-14"));
		assertThat(quote.nights()).extracting(NightPrice::price)
			.containsExactly(new BigDecimal("4000.00"), new BigDecimal("4600.00"), new BigDecimal("4600.00"));
		assertThat(quote.perRoomTotal()).isEqualTo(new BigDecimal("13200.00"));
		assertThat(quote.rooms()).isEqualTo(2);
		assertThat(quote.total()).isEqualTo(new BigDecimal("26400.00"));
	}

	@Test
	void refusesAStayWithAMissingNight() {
		// A gap would quietly make the stay cheaper
		assertThatIllegalArgumentException()
			.isThrownBy(() -> chain.quote(List.of(aNight().on("2026-03-12").build(), aNight().on("2026-03-14").build()), 1))
			.withMessageContaining("2026-03-13");
	}

	@Test
	void refusesNightsOutOfOrderAnEmptyStayOrNoRooms() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> chain.quote(List.of(aNight().on("2026-03-13").build(), aNight().on("2026-03-12").build()), 1));
		assertThatIllegalArgumentException().isThrownBy(() -> chain.quote(List.of(), 1));
		assertThatIllegalArgumentException().isThrownBy(() -> chain.quote(List.of(aNight().build()), 0));
	}

	@Test
	void theConfiguredChainAppliesEveryRule() {
		PricingProperties properties = new PricingProperties(
				new PricingProperties.Occupancy(new BigDecimal("0.80"), new BigDecimal("1.20")),
				new PricingProperties.Weekend(Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), new BigDecimal("1.15")),
				new PricingProperties.Holidays(Set.of(LocalDate.parse("2026-12-25")), new BigDecimal("1.25")));
		PricingChain configured = new PricingConfig().pricingChain(properties);

		// Christmas 2026 is a Friday: 4000.00 x surge 1.50 x busy 1.20 x weekend 1.15 x holiday 1.25
		BigDecimal price = configured
			.priceOf(aNight().on("2026-12-25").pricedAt("4000.00").withSurge("1.50").withSold(9, 10).build());

		assertThat(price).isEqualTo(new BigDecimal("10350.00"));
	}

}
