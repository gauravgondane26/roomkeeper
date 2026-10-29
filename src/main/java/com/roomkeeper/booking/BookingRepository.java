package com.roomkeeper.booking;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BookingRepository extends JpaRepository<Booking, Long> {

	@EntityGraph(attributePaths = { "room", "room.hotel" })
	Optional<Booking> findByIdAndUserId(Long id, Long userId);

	@EntityGraph(attributePaths = { "room", "room.hotel" })
	Page<Booking> findByUserId(Long userId, Pageable pageable);

	/** Every booking for any of the hotel's room types. */
	@EntityGraph(attributePaths = { "room", "room.hotel" })
	Page<Booking> findByRoomHotelId(Long hotelId, Pageable pageable);

	/**
	 * The user's booking, locked (SELECT ... FOR UPDATE) until the transaction ends, so the
	 * expiry job and the guest's own changes to it take turns.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select b from Booking b where b.id = :id and b.userId = :userId")
	Optional<Booking> lockOwned(Long id, Long userId);

	/**
	 * The booking, locked like {@link #lockOwned}. Only the booking row: its room and hotel
	 * load afterwards without locks, since bookings for the same room type are inserted
	 * while their nights are locked, and locking the room row here could deadlock with them.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select b from Booking b where b.id = :id")
	Optional<Booking> lockById(Long id);

	/** Bookings in one of {@code statuses} whose hold ran out by {@code now}, oldest first. */
	@Query("select b.id from Booking b where b.status in :statuses and b.holdExpiresAt <= :now order by b.holdExpiresAt")
	List<Long> findIdsHeldUntil(Collection<BookingStatus> statuses, Instant now, Limit limit);

}
