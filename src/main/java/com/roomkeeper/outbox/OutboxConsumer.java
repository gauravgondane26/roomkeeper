package com.roomkeeper.outbox;

import java.util.Set;

/**
 * Acts on published outbox events of some types. Delivery is at least once, so a consumer
 * can be handed the same event again and must tolerate that. Throwing leaves the event to
 * be retried on the next run.
 */
public interface OutboxConsumer {

	Set<OutboxEventType> types();

	void consume(OutboxEvent event);

}
