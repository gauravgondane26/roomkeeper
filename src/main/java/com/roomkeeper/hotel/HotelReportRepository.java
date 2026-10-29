package com.roomkeeper.hotel;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads a hotel's nights for its report: one query, one row per night in the range. */
@Repository
class HotelReportRepository {

	private final JdbcClient jdbc;

	HotelReportRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Every night from {@code from} to {@code to}: rooms booked (paid stays) and rooms on sale
	 * (a closed night counts only the rooms in use on it), from inventory; and the exact
	 * revenue it carries, each paid booking's amount less any refund over its nights. A night
	 * without inventory rows (not on sale) reads zero.
	 */
	List<HotelReport.Night> nights(long hotelId, LocalDate from, LocalDate to) {
		return jdbc.sql("""
				with nights as (
				    select cast(d as date) as date
				    from generate_series(cast(:from as date), cast(:to as date), interval '1 day') d
				),
				stock as (
				    select i.date, sum(i.booked_count) as booked,
				           sum(case when i.closed then i.booked_count else i.total_count end) as available
				    from inventory i
				    join rooms r on r.id = i.room_id
				    where r.hotel_id = :hotelId and i.date between :from and :to
				    group by i.date
				),
				kept as (
				    select b.check_in, b.check_out, sum(p.amount - coalesce(p.refunded_amount, 0)) as kept
				    from bookings b
				    join rooms r on r.id = b.room_id
				    join payments p on p.booking_id = b.id
				    where r.hotel_id = :hotelId and b.check_in <= :to and b.check_out > :from
				      and p.status in ('SUCCEEDED', 'REFUND_PENDING', 'REFUNDED')
				    group by b.id, b.check_in, b.check_out
				)
				select n.date, coalesce(s.booked, 0) as rooms_booked, coalesce(s.available, 0) as rooms_available,
				       coalesce((select sum(k.kept / (k.check_out - k.check_in)) from kept k
				                 where n.date >= k.check_in and n.date < k.check_out), 0) as revenue
				from nights n
				left join stock s on s.date = n.date
				order by n.date""")
			.param("hotelId", hotelId)
			.param("from", from)
			.param("to", to)
			.query((row, number) -> new HotelReport.Night(row.getObject("date", LocalDate.class),
					row.getInt("rooms_booked"), row.getInt("rooms_available"), row.getBigDecimal("revenue")))
			.list();
	}

}
