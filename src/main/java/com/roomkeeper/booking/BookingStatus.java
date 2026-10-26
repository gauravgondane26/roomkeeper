package com.roomkeeper.booking;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a booking is in its life. Everything before CONFIRMED is a hold on inventory
 * that can expire; a confirmed booking can only end by cancellation.
 */
public enum BookingStatus {

	RESERVED,
	GUESTS_ADDED,
	PAYMENT_PENDING,
	CONFIRMED,
	EXPIRED,
	CANCELLED;

	/** The statuses this one can move to, per the state diagram in the design doc. */
	private Set<BookingStatus> next() {
		return switch (this) {
			case RESERVED -> EnumSet.of(GUESTS_ADDED, EXPIRED);
			// Guests can be changed while the rooms are still held
			case GUESTS_ADDED -> EnumSet.of(GUESTS_ADDED, PAYMENT_PENDING, EXPIRED);
			case PAYMENT_PENDING -> EnumSet.of(CONFIRMED, EXPIRED);
			case CONFIRMED -> EnumSet.of(CANCELLED);
			case EXPIRED, CANCELLED -> EnumSet.noneOf(BookingStatus.class);
		};
	}

	public boolean canMoveTo(BookingStatus status) {
		return next().contains(status);
	}

	/** Not yet paid for: the rooms are held, counted in reserved_count, until the hold runs out. */
	public boolean isHold() {
		return this == RESERVED || this == GUESTS_ADDED || this == PAYMENT_PENDING;
	}

}
