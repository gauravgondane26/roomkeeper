package com.roomkeeper.booking;

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
	CANCELLED

}
