package com.roomkeeper.outbox;

import java.time.Instant;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractDataTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outbox table: the entity maps onto it, a new event waits to be published, and the
 * constraints refuse an event that's both published and parked as failed.
 */
class OutboxSchemaIT extends AbstractDataTest {

	private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

	@Autowired
	private OutboxEventRepository events;

	@Autowired
	private EntityManager entityManager;

	@Test
	void savesAnEventWaitingToBePublished() {
		long id = events.save(new OutboxEvent(42L, OutboxEventType.BOOKING_CONFIRMED,
				"{\"bookingId\":42,\"amount\":\"8000.00\"}", NOW)).getId();
		entityManager.flush();
		entityManager.clear();

		OutboxEvent event = events.findById(id).orElseThrow();

		assertThat(event.getAggregateId()).isEqualTo(42L);
		assertThat(event.getType()).isEqualTo(OutboxEventType.BOOKING_CONFIRMED);
		assertThat(event.getCreatedAt()).isEqualTo(NOW);
		assertThat(jdbc.queryForMap("select published_at, attempts, last_error, failed_at from outbox_events where id = ?",
				id))
			.containsEntry("published_at", null)
			.containsEntry("attempts", 0)
			.containsEntry("last_error", null)
			.containsEntry("failed_at", null);
		// Stored as JSON, not text, so its fields can be queried
		assertThat(jdbc.queryForObject("select payload ->> 'amount' from outbox_events where id = ?", String.class, id))
			.isEqualTo("8000.00");
	}

	@Test
	void anEventIsNeverBothPublishedAndParked() {
		assertThatThrownBy(() -> insertEvent(Map.of("published_at", "now()", "failed_at", "now()")))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("outbox_events_published_or_failed");
	}

	@Test
	void rejectsANegativeAttemptCount() {
		assertThatThrownBy(() -> insertEvent(Map.of("attempts", "-1")))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("outbox_events_attempts_check");
	}

	/** Inserts an event with plain SQL, with these columns set to these SQL expressions. */
	private void insertEvent(Map<String, String> columns) {
		jdbc.update("insert into outbox_events (aggregate_id, type, payload, created_at, "
				+ String.join(", ", columns.keySet()) + ") values (1, 'BOOKING_CONFIRMED', '{}', now(), "
				+ String.join(", ", columns.values()) + ")");
	}

}
