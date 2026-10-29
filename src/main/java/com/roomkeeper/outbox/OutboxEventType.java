package com.roomkeeper.outbox;

/** What happened, which decides what a consumer does with the event. */
public enum OutboxEventType {

	/** A payment confirmed a booking: the guest gets a confirmation. */
	BOOKING_CONFIRMED,

	/** The guest cancelled a booking: they're told what comes back. */
	BOOKING_CANCELLED,

	/** A cancellation made a refund due: it's made through the payment provider. */
	REFUND_DUE

}
