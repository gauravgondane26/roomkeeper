package com.roomkeeper.payment;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The Stripe events already applied. The marker lives in Postgres, not Redis, because it has
 * to commit or roll back with the change the event makes: a crash between two separate writes
 * could lose an event or apply it twice.
 */
@Repository
class ProcessedWebhookEvents {

	private final JdbcClient jdbc;

	ProcessedWebhookEvents(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Records the event in the caller's transaction; false if it was already recorded. A
	 * delivery racing another of the same event waits here until that one commits or rolls back.
	 */
	boolean recordFirstDelivery(String eventId, Instant receivedAt) {
		return jdbc.sql("""
				insert into processed_webhook_events (event_id, received_at) values (:eventId, :receivedAt)
				on conflict (event_id) do nothing""")
			.param("eventId", eventId)
			.param("receivedAt", Timestamp.from(receivedAt))
			.update() == 1;
	}

}
