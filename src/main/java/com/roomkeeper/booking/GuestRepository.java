package com.roomkeeper.booking;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestRepository extends JpaRepository<Guest, Long> {

	/** The user's saved guests, not counting removed ones. */
	List<Guest> findByUserIdAndRemovedAtIsNullOrderById(Long userId);

	Optional<Guest> findByIdAndUserIdAndRemovedAtIsNull(Long id, Long userId);

}
