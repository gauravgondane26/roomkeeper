package com.roomkeeper.outbox;

/** What happened, which decides what a consumer does with the event. */
public enum OutboxEventType {

	/** A payment confirmed a booking: the guest gets a confirmation. */
	BOOKING_CONFIRMED,

	/** The guest cancelled a booking: they're told what comes back. */
	BOOKING_CANCELLED,

	/** Rooms freed up and were held for a guest on the waitlist: they're told to pay in time. */
	WAITLIST_OFFERED,

	/** A cancellation made a refund due: it's made through the payment provider. */
	REFUND_DUE,

	/** Someone signed up with a new email: they're welcomed. */
	ACCOUNT_CREATED,

	/** Someone signed up with a registered email: its owner is told. */
	SIGNUP_FOR_EXISTING_ACCOUNT

}
