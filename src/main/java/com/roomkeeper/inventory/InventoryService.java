package com.roomkeeper.inventory;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates the per-night inventory rows that bookings and search work from, and keeps them in step with the room. */
@Service
public class InventoryService {

	/** How far ahead a room can be booked: tonight plus the following 364 nights. */
	public static final int NIGHTS_AHEAD = 365;

	private final JdbcClient jdbc;

	private final InventoryRepository inventory;

	public InventoryService(JdbcClient jdbc, InventoryRepository inventory) {
		this.jdbc = jdbc;
		this.inventory = inventory;
	}

	/**
	 * Opens a room for booking: one row per night for {@link #NIGHTS_AHEAD} nights from
	 * {@code firstNight} (the hotel's today), each with the room's total count. Nights that
	 * already have a row keep it, so calling this again only fills in what's missing.
	 * Returns how many nights it opened.
	 */
	@Transactional
	public int openYear(long roomId, int totalCount, LocalDate firstNight) {
		// One statement for the whole year; date + integer is a date, so no time zones involved
		return jdbc.sql("""
				insert into inventory (room_id, date, total_count)
				select :roomId, cast(:from as date) + n, :totalCount
				from generate_series(0, :nights - 1) as n
				on conflict (room_id, date) do nothing""")
			.param("roomId", roomId)
			.param("from", firstNight)
			.param("totalCount", totalCount)
			.param("nights", NIGHTS_AHEAD)
			.update();
	}

	/**
	 * A room type's total changed: every night from {@code firstNight} (the hotel's today)
	 * follows it, except nights whose total was set by hand. Rejected, changing nothing, if
	 * any of those nights already has more rooms booked or held than the new total.
	 */
	@Transactional
	public void applyRoomTotal(long roomId, int totalCount, LocalDate firstNight) {
		// Locked, so no booking can take a room between the check and the update
		List<Inventory> nights = inventory.lockNightsFollowingRoomTotal(roomId, firstNight);
		requireNotBelowSold(nights, totalCount);
		nights.forEach(night -> night.followRoomTotal(totalCount));
	}

	/** Throws {@link TotalBelowSoldException} naming every night with more rooms sold than {@code totalCount}. */
	static void requireNotBelowSold(List<Inventory> nights, int totalCount) {
		List<LocalDate> oversold = nights.stream()
			.filter(night -> night.sold() > totalCount)
			.map(Inventory::getDate)
			.toList();
		if (!oversold.isEmpty()) {
			throw new TotalBelowSoldException(totalCount, oversold);
		}
	}

}
