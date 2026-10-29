package com.roomkeeper.hotel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A report's totals are worked out from its nights. */
class HotelReportTest {

	private static final LocalDate MONDAY = LocalDate.parse("2026-03-09");

	@Test
	void totalsTheNights() {
		HotelReport report = HotelReport.of(MONDAY, MONDAY.plusDays(1), List.of(
				new HotelReport.Night(MONDAY, 3, 4, new BigDecimal("12000")),
				new HotelReport.Night(MONDAY.plusDays(1), 1, 4, new BigDecimal("4500"))));

		assertThat(report.roomNightsSold()).isEqualTo(4);
		assertThat(report.roomNightsAvailable()).isEqualTo(8);
		assertThat(report.occupancy()).isEqualByComparingTo("0.5");
		assertThat(report.revenue()).isEqualByComparingTo("16500.00");
		assertThat(report.averageDailyRate()).isEqualByComparingTo("4125.00");
	}

	@Test
	void roundsEachNightButTotalsTheExactRevenue() {
		// 1000.01 spread over two nights: 500.005 each, shown as 500.01, but the total stays 1000.01
		BigDecimal half = new BigDecimal("1000.01").divide(BigDecimal.TWO);
		HotelReport report = HotelReport.of(MONDAY, MONDAY.plusDays(1),
				List.of(new HotelReport.Night(MONDAY, 1, 1, half), new HotelReport.Night(MONDAY.plusDays(1), 1, 1, half)));

		assertThat(report.nights()).extracting(HotelReport.Night::revenue).containsExactly(
				new BigDecimal("500.01"), new BigDecimal("500.01"));
		assertThat(report.revenue()).isEqualByComparingTo("1000.01");
	}

	@Test
	void occupancyIsAFractionToFourPlaces() {
		HotelReport report = HotelReport.of(MONDAY, MONDAY,
				List.of(new HotelReport.Night(MONDAY, 1, 3, BigDecimal.ZERO)));

		assertThat(report.occupancy()).isEqualByComparingTo("0.3333");
	}

	@Test
	void withNothingOnSaleOrSoldThereIsNoRate() {
		HotelReport report = HotelReport.of(MONDAY, MONDAY,
				List.of(new HotelReport.Night(MONDAY, 0, 0, BigDecimal.ZERO)));

		assertThat(report.occupancy()).isEqualByComparingTo("0");
		assertThat(report.averageDailyRate()).isNull();
	}

}
