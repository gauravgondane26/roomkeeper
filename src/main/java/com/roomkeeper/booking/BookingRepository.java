package com.roomkeeper.booking;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BookingRepository extends JpaRepository<Booking, Long> {

	/**
	 * The user's booking, locked (SELECT ... FOR UPDATE) until the transaction ends, so the
	 * expiry job and the guest's own changes to it take turns.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select b from Booking b where b.id = :id and b.userId = :userId")
	Optional<Booking> lockOwned(Long id, Long userId);

}
