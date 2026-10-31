package com.roomkeeper.outbox;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saves outbox events. Only inside the transaction making the change: the event commits
 * with it or not at all, so it's never lost and never sent for a change that rolled back.
 * Once that transaction commits, the relay is woken to send the event to its stream.
 */
@Component
public class OutboxWriter {

	private final OutboxEventRepository events;

	private final OutboxRelay relay;

	private final JsonMapper json;

	private final Clock clock;

	OutboxWriter(OutboxEventRepository events, OutboxRelay relay, JsonMapper json, Clock clock) {
		this.events = events;
		this.relay = relay;
		this.json = json;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void write(OutboxPayload payload) {
		events.save(new OutboxEvent(payload.aggregateId(), payload.type(), json.writeValueAsString(payload),
				clock.instant()));
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				relay.wake();
			}
		});
	}

}
