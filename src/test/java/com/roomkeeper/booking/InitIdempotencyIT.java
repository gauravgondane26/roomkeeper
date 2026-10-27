package com.roomkeeper.booking;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client that retries POST /bookings/init (after a timeout, or a double click) with the
 * same Idempotency-Key gets the booking it already made, not a second hold on the rooms.
 */
@Import(MutableClockConfig.class)
class InitIdempotencyIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	private String guest;

	private long roomId;

	@BeforeEach
	void openAHotel() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		guest = signUpAndLogIn(aUser().build());
		roomId = firstRoomOf(manager, activeHotel(manager, aHotel().build(), aRoom().withTotalCount(5).build()));
	}

	@Test
	void aRetriedInitWithTheSameKeyReturnsTheOriginalBooking() {
		ExtractableResponse<Response> first = init("retry-me").statusCode(201).extract();

		ExtractableResponse<Response> retry = init("retry-me").statusCode(201).extract();

		assertThat(retry.jsonPath().getLong("id")).isEqualTo(first.jsonPath().getLong("id"));
		assertThat(retry.header("Location")).isEqualTo(first.header("Location"));
		assertThat(retry.header("Idempotent-Replayed")).isEqualTo("true");
		assertThat(jdbc.queryForObject("select count(*) from bookings where room_id = ?", Long.class, roomId))
			.isEqualTo(1);
		assertThat(jdbc.queryForObject("select reserved_count from inventory where room_id = ? and date = ?",
				Integer.class, roomId, LocalDate.parse("2026-03-10")))
			.as("the rooms are held once")
			.isEqualTo(2);
	}

	private ValidatableResponse init(String idempotencyKey) {
		return api(guest).header("Idempotency-Key", idempotencyKey).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", "2026-03-10", "checkOut", "2026-03-12", "rooms", 2))
			.when().post("/api/v1/bookings/init")
			.then();
	}

}
