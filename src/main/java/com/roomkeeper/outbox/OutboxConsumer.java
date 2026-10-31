package com.roomkeeper.outbox;

import java.util.Set;

/**
 * Acts on outbox events of some types, which reach it through its own Redis stream
 * ({@code outbox:{name}}, read by a consumer group of the same name). Delivery is at least
 * once, so a consumer can be handed the same event again and must tolerate that. It's
 * called inside the transaction that marks the event published; throwing rolls that back
 * and leaves the event to be tried again.
 */
public interface OutboxConsumer {

	/** Names its stream and consumer group: lowercase words joined by dashes. */
	String name();

	Set<OutboxEventType> types();

	void consume(OutboxEvent event);

}
