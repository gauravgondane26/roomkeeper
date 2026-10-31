package com.roomkeeper.common;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.roomkeeper.common.error.InvalidFieldException;

/**
 * How long a stay may be: 1 to {@value #MAX_NIGHTS} nights. Search and booking both check
 * it here, so search never offers a stay that booking would refuse, or hides one it would take.
 */
public final class Stays {

	public static final int MAX_NIGHTS = 30;

	private Stays() {
	}

	/** The stay's nights, after checking there are 1 to {@value #MAX_NIGHTS} of them. */
	public static long nights(LocalDate checkIn, LocalDate checkOut) {
		long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
		if (nights < 1) {
			throw new InvalidFieldException("checkOut", "must be after checkIn");
		}
		if (nights > MAX_NIGHTS) {
			throw new InvalidFieldException("checkOut", "must be at most " + MAX_NIGHTS + " nights after checkIn");
		}
		return nights;
	}

}
