package com.roomkeeper.booking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;

/**
 * What a guest gets back for cancelling a confirmed booking, by how much notice they give:
 * everything with at least 48 hours before check-in, half with less, and no cancelling
 * once the stay has begun. Notice is counted in hours that actually pass, so a clock
 * change in between doesn't shift it.
 */
public final class CancellationPolicy {

	/** When a stay begins on its check-in date, in the hotel's time zone. */
	public static final LocalTime CHECK_IN_TIME = LocalTime.of(14, 0);

	/** Cancelling with at least this much notice refunds everything. */
	public static final Duration FULL_REFUND_NOTICE = Duration.ofHours(48);

	/** The share refunded with less notice. */
	public static final BigDecimal LATE_REFUND_SHARE = new BigDecimal("0.5");

	private CancellationPolicy() {
	}

	/** The moment a stay checking in on {@code checkIn} begins, where the hotel is. */
	public static Instant checkInMoment(LocalDate checkIn, ZoneId hotelZone) {
		return ZonedDateTime.of(checkIn, CHECK_IN_TIME, hotelZone).toInstant();
	}

	/**
	 * The refund for cancelling at {@code now} a stay paid {@code paid}: all of it with
	 * enough notice, otherwise half, rounded to the minor unit in the guest's favour. Throws
	 * (409 cancellation-closed) once the stay has begun.
	 */
	public static BigDecimal refund(BigDecimal paid, LocalDate checkIn, ZoneId hotelZone, Instant now) {
		Instant begins = checkInMoment(checkIn, hotelZone);
		if (!now.isBefore(begins)) {
			throw new ApiException(ProblemType.CANCELLATION_CLOSED,
					"The stay began at " + begins + "; it can no longer be cancelled");
		}
		if (!now.isAfter(begins.minus(FULL_REFUND_NOTICE))) {
			return paid;
		}
		return paid.multiply(LATE_REFUND_SHARE).setScale(paid.scale(), RoundingMode.HALF_UP);
	}

}
