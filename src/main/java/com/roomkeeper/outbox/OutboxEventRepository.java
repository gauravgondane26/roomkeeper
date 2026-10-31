package com.roomkeeper.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

	/**
	 * Events after {@code afterId} not yet sent to their stream, oldest first (read through
	 * the partial index of unpublished events).
	 */
	@Query("select e.id from OutboxEvent e where e.publishedAt is null and e.failedAt is null and e.sentAt is null"
			+ " and e.id > :afterId order by e.id")
	List<Long> findUnsentIdsAfter(long afterId, Limit limit);

	/**
	 * The event if it's still unsent, locked until the transaction ends. Empty if it was sent,
	 * published or parked meanwhile, or another relay holds it right now (skipped, not waited
	 * for, so relays that overlap split the queue instead of sending an event twice).
	 */
	@Query(value = "select * from outbox_events where id = :id and sent_at is null and published_at is null"
			+ " and failed_at is null for update skip locked", nativeQuery = true)
	Optional<OutboxEvent> lockUnsent(long id);

	/**
	 * The event, locked until the transaction ends, whatever its state. Empty if another
	 * delivery holds it right now (or it doesn't exist): skipped, not waited for.
	 */
	@Query(value = "select * from outbox_events where id = :id for update skip locked", nativeQuery = true)
	Optional<OutboxEvent> lockById(long id);

	/** Marks the event unsent again, unless it was published meanwhile: its stream never got it. */
	@Modifying
	@Query(value = "update outbox_events set sent_at = null where id = :id and published_at is null", nativeQuery = true)
	int unsend(long id);

	/**
	 * Marks unsent the events sent before {@code cutoff} and still not published, so the
	 * relay sends them again: their stream lost them, or no reader got to them in time.
	 * Returns how many.
	 */
	@Modifying
	@Query(value = "update outbox_events set sent_at = null where published_at is null and failed_at is null"
			+ " and sent_at < :cutoff", nativeQuery = true)
	int resendSentBefore(Instant cutoff);

}
