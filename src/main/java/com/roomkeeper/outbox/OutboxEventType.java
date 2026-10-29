package com.roomkeeper.outbox;

/** What happened, which decides what a consumer does with the event. */
public enum OutboxEventType {

	/** A payment confirmed a booking: the guest gets a confirmation. */
	BOOKING_CONFIRMED

}
