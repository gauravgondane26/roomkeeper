package com.roomkeeper.booking;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, Long> {

	/** The room type's line: waiting entries, oldest first (the partial index's order). */
	@Query("select e.id from WaitlistEntry e where e.room.id = :roomId and e.status = :status"
			+ " order by e.createdAt, e.id")
	List<Long> findIdsInLine(long roomId, WaitlistStatus status);

	/**
	 * The entry if it's still waiting, locked until the transaction ends. Empty if it was
	 * offered, left or expired meanwhile, or another transaction holds it (skipped, not waited
	 * for: the transaction offering rooms already holds nights, and mustn't wait).
	 */
	@Query(value = "select * from waitlist_entries where id = :id and status = 'WAITING' for update skip locked",
			nativeQuery = true)
	Optional<WaitlistEntry> lockWaiting(long id);

	/** The user's entry, locked until the transaction ends; empty if it isn't theirs. */
	@Query(value = "select * from waitlist_entries where id = :id and user_id = :userId for update", nativeQuery = true)
	Optional<WaitlistEntry> lockOwned(long id, long userId);

	List<WaitlistEntry> findByUserIdOrderByCreatedAtDescIdDesc(long userId);

	// A query, not a derived name: Spring Data reads "CheckIn" in one as a property "check" and "In"
	@Query("select count(e) > 0 from WaitlistEntry e where e.userId = :userId and e.room.id = :roomId"
			+ " and e.checkIn = :checkIn and e.checkOut = :checkOut and e.status = :status")
	boolean isInLine(long userId, long roomId, LocalDate checkIn, LocalDate checkOut, WaitlistStatus status);

}
