package com.roomkeeper.booking;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestRepository extends JpaRepository<Guest, Long> {

	/** The user's saved guests, not counting removed ones. */
	List<Guest> findByUserIdAndRemovedAtIsNullOrderById(Long userId);

	Optional<Guest> findByIdAndUserIdAndRemovedAtIsNull(Long id, Long userId);

	/** Those of the user's saved guests that have these ids, in no particular order. */
	List<Guest> findByIdInAndUserIdAndRemovedAtIsNull(Collection<Long> ids, Long userId);

}
