package com.roomkeeper.inventory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * The inventory roll keeps every room type on sale a year ahead: as each hotel's day
 * turns, a new last night opens, and any night that went missing comes back.
 */
@Import(MutableClockConfig.class)
class InventoryRollIT extends AbstractIntegrationTest {

	private static final Instant TUESDAY_MORNING = Instant.parse("2026-03-10T09:00:00Z");

	@Autowired
	MutableClock clock;

	@Autowired
	InventoryRollJob job;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	StringRedisTemplate redis;

	private String manager;

	@BeforeEach
	void logIn() {
		clock.setInstant(TUESDAY_MORNING);
		manager = signUpAndLogIn(aUser().asHotelManager().build());
	}

	@Test
	void opensTheNextNightAsTheDayTurns() {
		long hotel = activeHotel(manager, aHotel().build(), aRoom().withTotalCount(4).build());
		long room = firstRoomOf(manager, hotel);
		assertThat(lastNight(room)).isEqualTo("2027-03-09");

		clock.advance(Duration.ofDays(1));
		job.run();

		assertThat(lastNight(room)).isEqualTo("2027-03-10");
		assertThat(nightsFrom(room, "2026-03-11")).isEqualTo(365);
		assertThat(jdbc.queryForObject("select total_count from inventory where room_id = ? and date = '2027-03-10'",
				Integer.class, room)).isEqualTo(4);
	}

	@Test
	void followsEachHotelsOwnMidnight() {
		long hotel = activeHotel(manager, aHotel().inTimeZone("Pacific/Kiritimati").build(), aRoom().build());
		long room = firstRoomOf(manager, hotel);
		assertThat(lastNight(room)).isEqualTo("2027-03-09");

		// 10:00 UTC on Tuesday is already Wednesday in Kiritimati (UTC+14), not yet in UTC
		clock.setInstant(Instant.parse("2026-03-10T10:00:00Z"));
		job.run();

		assertThat(lastNight(room)).isEqualTo("2027-03-10");
	}

	@Test
	void fillsInMissingNights() {
		long room = firstRoomOf(manager, activeHotel(manager, aHotel().build(), aRoom().build()));
		jdbc.update("delete from inventory where room_id = ? and date = '2026-06-01'", room);

		job.run();

		assertThat(nightsFrom(room, "2026-03-10")).isEqualTo(365);
	}

	@Test
	void leavesExistingNightsAsTheyAre() {
		long room = firstRoomOf(manager, activeHotel(manager, aHotel().build(), aRoom().withTotalCount(5).build()));
		api(manager).contentType(ContentType.JSON)
			.body(Map.of("from", "2026-06-01", "to", "2026-06-01", "totalCount", 2, "closed", true))
			.when().patch("/api/v1/admin/inventory/rooms/{roomId}", room)
			.then().statusCode(200);

		job.run();

		nightsOf(manager, room, "2026-06-01", "2026-06-01")
			.body("[0].totalCount", equalTo(2))
			.body("[0].closed", equalTo(true));
	}

	@Test
	void leavesHotelsThatArentOnSaleAlone() {
		long hotel = createHotel(manager, aHotel().build());
		long room = createRoom(manager, hotel, aRoom().build());

		job.run();

		assertThat(nightsFrom(room, "2026-03-10")).isZero();
	}

	@Test
	void pricesTheNewNights() {
		long hotel = activeHotel(manager, aHotel().build(), aRoom().pricedAt("4000").build());
		clock.advance(Duration.ofDays(1));

		job.run();

		// 2027-03-10 is a Wednesday
		assertThat(jdbc.queryForObject("select min_price from hotel_min_price where hotel_id = ? and date = ?",
				BigDecimal.class, hotel, LocalDate.parse("2027-03-10"))).isEqualByComparingTo("4000.00");
	}

	@Test
	void skipsTheRunWhileAnotherInstanceHoldsTheLock() {
		long room = firstRoomOf(manager, activeHotel(manager, aHotel().build(), aRoom().build()));
		clock.advance(Duration.ofDays(1));
		redis.opsForValue().set("lock:inventory-roll", "another-instance", Duration.ofSeconds(30));

		job.run();

		assertThat(lastNight(room)).isEqualTo("2027-03-09");
	}

	private String lastNight(long roomId) {
		return jdbc.queryForObject("select max(date)::text from inventory where room_id = ?", String.class, roomId);
	}

	private int nightsFrom(long roomId, String from) {
		return jdbc.queryForObject("select count(*) from inventory where room_id = ? and date >= ?::date",
				Integer.class, roomId, from);
	}

}
