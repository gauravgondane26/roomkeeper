package com.roomkeeper.search;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Finds hotels in a city with a room type that has enough free rooms on every night of a
 * stay. Plain SQL: the availability check is a GROUP BY over inventory with
 * {@code HAVING COUNT(*) = nights}, which a JPA query would only obscure.
 */
@Repository
public class HotelSearchRepository {

	/**
	 * Room types (and their hotels) free on every night of the stay. A night counts only if
	 * it is open and has enough rooms left; a missing night (past the inventory window)
	 * simply isn't counted, so it fails the HAVING too. The check-in must not have passed
	 * in the hotel's own time zone.
	 */
	private static final String AVAILABLE_HOTELS = """
			with available_hotels as (
			    select distinct r.hotel_id
			    from inventory i
			    join rooms r on r.id = i.room_id
			    join hotels h on h.id = r.hotel_id
			    where lower(h.city) = lower(:city) and h.active
			      and cast(:checkIn as date) >= cast(cast(:now as timestamptz) at time zone h.time_zone as date)
			      and i.date >= :checkIn and i.date < :checkOut
			      and not i.closed
			      and i.total_count - i.booked_count - i.reserved_count >= :rooms
			    group by r.hotel_id, r.id
			    having count(*) = :nights
			)
			""";

	private final JdbcClient jdbc;

	public HotelSearchRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** One page of matches and how many hotels match in all. */
	public record Page(List<HotelMatch> hotels, long total) {
	}

	private record Row(HotelMatch match, long total) {
	}

	/**
	 * One page of matches, cheapest first (hotels without a price last), then by id. The
	 * total comes with each row ({@code count(*) over ()} counts every match before the
	 * limit), so a page takes one query. A page past the last has no row to carry it, so
	 * only then is it counted separately.
	 */
	public Page search(SearchCriteria criteria, Instant now, int limit, long offset) {
		List<Row> rows = jdbc.sql(AVAILABLE_HOTELS + """
				select h.id, h.name, h.city, h.amenities, min(p.min_price) as from_price,
				       count(*) over () as total
				from hotels h
				join available_hotels a on a.hotel_id = h.id
				left join hotel_min_price p
				       on p.hotel_id = h.id and p.date >= :checkIn and p.date < :checkOut
				group by h.id
				order by from_price nulls last, h.id
				limit :limit offset :offset""")
			.params(params(criteria, now))
			.param("limit", limit)
			.param("offset", offset)
			.query((rs, row) -> new Row(toMatch(rs), rs.getLong("total")))
			.list();
		if (rows.isEmpty()) {
			return new Page(List.of(), offset > 0 ? count(criteria, now) : 0);
		}
		return new Page(rows.stream().map(Row::match).toList(), rows.getFirst().total());
	}

	private long count(SearchCriteria criteria, Instant now) {
		return jdbc.sql(AVAILABLE_HOTELS + "select count(*) from available_hotels")
			.params(params(criteria, now))
			.query(Long.class)
			.single();
	}

	private static Map<String, Object> params(SearchCriteria criteria, Instant now) {
		return Map.of("city", criteria.city(), "checkIn", criteria.checkIn(), "checkOut", criteria.checkOut(),
				"rooms", criteria.rooms(), "nights", criteria.nights(),
				// The driver takes an OffsetDateTime for timestamptz, not an Instant
				"now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
	}

	private static HotelMatch toMatch(ResultSet rs) throws SQLException {
		Array amenities = rs.getArray("amenities");
		return new HotelMatch(rs.getLong("id"), rs.getString("name"), rs.getString("city"),
				List.of((String[]) amenities.getArray()), rs.getBigDecimal("from_price"));
	}

}
