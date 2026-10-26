package com.roomkeeper.inventory;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates the per-night inventory rows that bookings and search work from. */
@Service
public class InventoryService {

	/** How far ahead a room can be booked: tonight plus the following 364 nights. */
	public static final int NIGHTS_AHEAD = 365;

	private final JdbcClient jdbc;

	private final Clock clock;

	public InventoryService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/**
	 * Opens a room for booking: one row per night from today for {@link #NIGHTS_AHEAD}
	 * nights, each with the room's total count. Nights that already have a row keep it, so
	 * calling this again only fills in what's missing.
	 */
	@Transactional
	public void openYear(long roomId, int totalCount) {
		// One statement for the whole year; date + integer is a date, so no time zones involved
		jdbc.sql("""
				insert into inventory (room_id, date, total_count)
				select :roomId, cast(:from as date) + n, :totalCount
				from generate_series(0, :nights - 1) as n
				on conflict (room_id, date) do nothing""")
			.param("roomId", roomId)
			.param("from", LocalDate.now(clock))
			.param("totalCount", totalCount)
			.param("nights", NIGHTS_AHEAD)
			.update();
	}

}
