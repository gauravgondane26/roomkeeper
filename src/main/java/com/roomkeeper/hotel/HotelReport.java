package com.roomkeeper.hotel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * A hotel's nights from {@code from} to {@code to}, both included, and their totals.
 * Revenue is counted by night stayed: each paid booking's amount, less any refund, spread
 * evenly over its nights. {@code occupancy} is rooms sold over rooms on sale (a fraction);
 * {@code averageDailyRate} is revenue per room night sold, null when none were.
 */
public record HotelReport(LocalDate from, LocalDate to, BigDecimal revenue, int roomNightsSold,
		int roomNightsAvailable, BigDecimal occupancy, BigDecimal averageDailyRate, List<Night> nights) {

	/** One night: rooms booked (paid stays), rooms on sale or in use, and the revenue it carries. */
	public record Night(LocalDate date, int roomsBooked, int roomsAvailable, BigDecimal revenue) {
	}

	/**
	 * Totals the nights, whose revenue is exact (a booking's amount over its nights can run
	 * past the paisa). Each night is shown to the paisa; the total is the exact sum, rounded
	 * once, so it doesn't drift from what was paid.
	 */
	static HotelReport of(LocalDate from, LocalDate to, List<Night> nights) {
		int sold = nights.stream().mapToInt(Night::roomsBooked).sum();
		int available = nights.stream().mapToInt(Night::roomsAvailable).sum();
		BigDecimal revenue = nights.stream().map(Night::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal occupancy = available == 0 ? BigDecimal.ZERO.setScale(4)
				: BigDecimal.valueOf(sold).divide(BigDecimal.valueOf(available), 4, RoundingMode.HALF_UP);
		BigDecimal averageDailyRate = sold == 0 ? null
				: revenue.divide(BigDecimal.valueOf(sold), 2, RoundingMode.HALF_UP);
		List<Night> shown = nights.stream()
			.map(night -> new Night(night.date(), night.roomsBooked(), night.roomsAvailable(), money(night.revenue())))
			.toList();
		return new HotelReport(from, to, money(revenue), sold, available, occupancy, averageDailyRate, shown);
	}

	private static BigDecimal money(BigDecimal amount) {
		return amount.setScale(2, RoundingMode.HALF_UP);
	}

}
