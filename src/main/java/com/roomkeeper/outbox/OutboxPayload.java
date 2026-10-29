package com.roomkeeper.outbox;

/** What an outbox event carries, stored as JSON. Each type of event has its own record. */
public interface OutboxPayload {

	OutboxEventType type();

	/** What the event is about: the booking or user its type names. */
	long aggregateId();

}
