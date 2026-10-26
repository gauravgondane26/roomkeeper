package com.roomkeeper.inventory;

import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

	List<Inventory> findByRoomIdAndDateBetweenOrderByDate(Long roomId, LocalDate from, LocalDate to);

	/**
	 * The room's nights in the range, locked (SELECT ... FOR UPDATE) until the transaction
	 * ends. Always locked in date order, so two transactions over overlapping ranges can't
	 * deadlock each other.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from Inventory i where i.roomId = :roomId and i.date between :from and :to order by i.date")
	List<Inventory> lockNights(Long roomId, LocalDate from, LocalDate to);

	/** Like {@link #lockNights}: every night from {@code from} on whose total wasn't set by hand. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select i from Inventory i
			where i.roomId = :roomId and i.date >= :from and i.totalOverridden = false
			order by i.date""")
	List<Inventory> lockNightsFollowingRoomTotal(Long roomId, LocalDate from);

}
