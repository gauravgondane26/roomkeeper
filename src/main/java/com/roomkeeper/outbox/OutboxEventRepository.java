package com.roomkeeper.outbox;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

	/** Waiting events after {@code afterId}, oldest first (the partial index's queue). */
	@Query("select e.id from OutboxEvent e where e.publishedAt is null and e.failedAt is null and e.id > :afterId"
			+ " order by e.id")
	List<Long> findWaitingIdsAfter(long afterId, Limit limit);

	/**
	 * The event if it's still waiting, locked until the transaction ends. Empty if it was
	 * published or parked meanwhile, or another run holds it right now (skipped, not waited
	 * for, so overlapping runs split the queue instead of sending an event twice).
	 */
	@Query(value = "select * from outbox_events where id = :id and published_at is null and failed_at is null"
			+ " for update skip locked", nativeQuery = true)
	Optional<OutboxEvent> lockWaiting(long id);

}
