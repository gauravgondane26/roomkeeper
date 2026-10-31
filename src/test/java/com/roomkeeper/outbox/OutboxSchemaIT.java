package com.roomkeeper.outbox;

import java.sql.Timestamp;
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

		assertThat(event.getType()).isEqualTo(OutboxEventType.BOOKING_CONFIRMED);
		assertThat(jdbc.queryForMap("select aggregate_id, created_at, sent_at, published_at, attempts, last_error,"
				+ " failed_at from outbox_events where id = ?", id))
			.containsEntry("aggregate_id", 42L)
			.containsEntry("created_at", Timestamp.from(NOW))
			.containsEntry("sent_at", null)
			.containsEntry("published_at", null)
			.containsEntry("attempts", 0)
			.containsEntry("last_error", null)
			.containsEntry("failed_at", null);
		// Stored as JSON, not text, so its fields can be queried
		assertThat(jdbc.queryForObject("select payload ->> 'amount' from outbox_events where id = ?", String.class, id))
			.isEqualTo("8000.00");
	}

	@Test
	void recordsWhenAnEventWasSentAndAFailureMovesThatOn() {
		OutboxEvent event = events.save(new OutboxEvent(42L, OutboxEventType.BOOKING_CONFIRMED, "{}", NOW));
		event.markSent(NOW.plusSeconds(1));
		entityManager.flush();
		assertThat(sentAt(event.getId())).isEqualTo(Timestamp.from(NOW.plusSeconds(1)));

		// A reader had it, so the stream still holds it: the wait before resending starts again
		event.recordFailure("IllegalStateException: down", NOW.plusSeconds(40));
		entityManager.flush();

		assertThat(sentAt(event.getId())).isEqualTo(Timestamp.from(NOW.plusSeconds(40)));
	}

	@Test
	void aFailureKeepsTheFirstThousandCharactersOfALongError() {
		OutboxEvent event = events.save(new OutboxEvent(42L, OutboxEventType.BOOKING_CONFIRMED, "{}", NOW));
		event.recordFailure("IllegalStateException: " + "x".repeat(5_000), NOW);
		entityManager.flush();

		String stored = jdbc.queryForObject("select last_error from outbox_events where id = ?", String.class,
				event.getId());
		assertThat(stored).hasSize(1_000)
			.startsWith("IllegalStateException: xxx");
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

	private Timestamp sentAt(long id) {
		return jdbc.queryForObject("select sent_at from outbox_events where id = ?", Timestamp.class, id);
	}

	/** Inserts an event with plain SQL, with these columns set to these SQL expressions. */
	private void insertEvent(Map<String, String> columns) {
		jdbc.update("insert into outbox_events (aggregate_id, type, payload, created_at, "
				+ String.join(", ", columns.keySet()) + ") values (1, 'BOOKING_CONFIRMED', '{}', now(), "
				+ String.join(", ", columns.values()) + ")");
	}

}
