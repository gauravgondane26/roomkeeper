package com.roomkeeper.outbox;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saves outbox events. Only inside the transaction making the change: the event commits
 * with it or not at all, so it's never lost and never sent for a change that rolled back.
 */
@Component
public class OutboxWriter {

	private final OutboxEventRepository events;

	private final JsonMapper json;

	private final Clock clock;

	OutboxWriter(OutboxEventRepository events, JsonMapper json, Clock clock) {
		this.events = events;
		this.json = json;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void write(OutboxPayload payload) {
		events.save(new OutboxEvent(payload.aggregateId(), payload.type(), json.writeValueAsString(payload),
				clock.instant()));
	}

}
