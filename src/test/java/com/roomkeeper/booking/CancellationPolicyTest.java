package com.roomkeeper.booking;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What cancelling gives back: everything with 48 hours' notice before check-in, half with
 * less, and nothing once the stay has begun at 2 PM hotel time on the check-in date.
 */
class CancellationPolicyTest {

	private static final ZoneId GOA = ZoneId.of("Asia/Kolkata");

	private static final LocalDate CHRISTMAS_EVE = LocalDate.parse("2026-12-24");

	/** 24 December, 14:00 in Goa. */
	private static final Instant CHECK_IN = Instant.parse("2026-12-24T08:30:00Z");

	private static final BigDecimal PAID = new BigDecimal("9000.00");

	@Test
	void theStayBeginsAtTwoInTheAfternoonHotelTime() {
		assertThat(CancellationPolicy.checkInMoment(CHRISTMAS_EVE, GOA)).isEqualTo(CHECK_IN);
	}

	@Test
	void refundsEverythingWithFortyEightHoursNotice() {
		assertThat(refundAt(CHECK_IN.minus(Duration.ofDays(30)))).isEqualByComparingTo("9000.00");
		assertThat(refundAt(CHECK_IN.minus(Duration.ofHours(48)))).isEqualByComparingTo("9000.00");
	}

	@Test
	void refundsHalfWithLessNotice() {
		assertThat(refundAt(CHECK_IN.minus(Duration.ofHours(48)).plusSeconds(1))).isEqualByComparingTo("4500.00");
		assertThat(refundAt(CHECK_IN.minusSeconds(1))).isEqualByComparingTo("4500.00");
	}

	@Test
	void cantCancelOnceTheStayHasBegun() {
		for (Instant now : new Instant[] { CHECK_IN, CHECK_IN.plus(Duration.ofDays(1)) }) {
			assertThatThrownBy(() -> refundAt(now)).isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.type()).isEqualTo(ProblemType.CANCELLATION_CLOSED));
		}
	}

	@Test
	void halfIsRoundedToThePaisaInTheGuestsFavour() {
		assertThat(CancellationPolicy.refund(new BigDecimal("9000.01"), CHRISTMAS_EVE, GOA, CHECK_IN.minusSeconds(1)))
			.isEqualByComparingTo("4500.01");
	}

	@Test
	void noticeIsCountedInHoursThatPassEvenAcrossAClockChange() {
		// New York's clocks go back an hour on 1 November 2026, so 48 hours before
		// 2 November at 14:00 (EST) is 31 October at 15:00 (EDT), not 14:00
		ZoneId newYork = ZoneId.of("America/New_York");
		LocalDate checkIn = LocalDate.parse("2026-11-02");
		Instant lastFullRefund = ZonedDateTime.parse("2026-10-31T15:00-04:00[America/New_York]").toInstant();

		assertThat(CancellationPolicy.refund(PAID, checkIn, newYork, lastFullRefund)).isEqualByComparingTo("9000.00");
		assertThat(CancellationPolicy.refund(PAID, checkIn, newYork, lastFullRefund.plusSeconds(1)))
			.isEqualByComparingTo("4500.00");
	}

	private static BigDecimal refundAt(Instant now) {
		return CancellationPolicy.refund(PAID, CHRISTMAS_EVE, GOA, now);
	}

}
