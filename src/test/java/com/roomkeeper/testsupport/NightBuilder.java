package com.roomkeeper.testsupport;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.roomkeeper.pricing.Night;

/**
 * Test data builder for a night to price. Defaults: a Tuesday, base price 4000.00, no
 * surge, 10 rooms with none sold. Tests only state what matters to them:
 * {@code aNight().on("2026-03-13").withSurge("1.5").build()}.
 */
public final class NightBuilder {

	private LocalDate date = LocalDate.parse("2026-03-10");

	private BigDecimal basePrice = new BigDecimal("4000.00");

	private BigDecimal surgeFactor = new BigDecimal("1.00");

	private int totalCount = 10;

	private int soldCount;

	private NightBuilder() {
	}

	public static NightBuilder aNight() {
		return new NightBuilder();
	}

	public NightBuilder on(String date) {
		this.date = LocalDate.parse(date);
		return this;
	}

	public NightBuilder pricedAt(String basePrice) {
		this.basePrice = new BigDecimal(basePrice);
		return this;
	}

	public NightBuilder withSurge(String surgeFactor) {
		this.surgeFactor = new BigDecimal(surgeFactor);
		return this;
	}

	/** {@code sold} of {@code total} rooms booked or held. */
	public NightBuilder withSold(int sold, int total) {
		this.soldCount = sold;
		this.totalCount = total;
		return this;
	}

	public Night build() {
		return new Night(date, basePrice, surgeFactor, totalCount, soldCount);
	}

}
