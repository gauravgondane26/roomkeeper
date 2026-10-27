package com.roomkeeper.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;

/** Every instance schedules the hold expiry job; only the one holding lock:hold-expiry runs it. */
@Import(MutableClockConfig.class)
class HoldExpiryLockIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	@Autowired
	HoldExpiryJob job;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void skipsTheRunWhileAnotherInstanceHoldsTheLock() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		long roomId = firstRoomOf(manager, activeHotel(manager, aHotel().build(), aRoom().build()));
		long bookingId = idempotentApi(signUpAndLogIn(aUser().build())).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", "2026-03-10", "checkOut", "2026-03-11", "rooms", 1))
			.when().post("/api/v1/bookings/init")
			.then().statusCode(201).extract().jsonPath().getLong("id");
		clock.advance(Duration.ofMinutes(10));

		redis.opsForValue().set("lock:hold-expiry", "another-instance", Duration.ofSeconds(30));
		job.run();

		assertThat(status(bookingId)).isEqualTo("RESERVED");

		redis.delete("lock:hold-expiry");
		job.run();

		assertThat(status(bookingId)).isEqualTo("EXPIRED");
	}

	private String status(long bookingId) {
		return jdbc.queryForObject("select status from bookings where id = ?", String.class, bookingId);
	}

}
