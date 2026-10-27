package com.roomkeeper.booking;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

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

import static com.roomkeeper.testsupport.Concurrently.runTogether;
import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

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

	@Test
	void aNewKeyMakesANewBooking() {
		long first = init("first").statusCode(201).extract().jsonPath().getLong("id");

		long second = init("second").statusCode(201).extract().jsonPath().getLong("id");

		assertThat(second).isNotEqualTo(first);
		assertThat(reservedOnTheFirstNight()).isEqualTo(4);
	}

	@Test
	void withoutAKeyNothingIsHeld() {
		api(guest).contentType(ContentType.JSON).body(stay(2))
			.when().post("/api/v1/bookings/init")
			.then()
				.statusCode(400)
				.body("type", equalTo("urn:roomkeeper:problem:idempotency-key-required"));

		assertThat(reservedOnTheFirstNight()).isZero();
	}

	@Test
	void theSameKeyForAnotherStayIsRefused() {
		init("retry-me").statusCode(201);

		api(guest).header("Idempotency-Key", "retry-me").contentType(ContentType.JSON).body(stay(1))
			.when().post("/api/v1/bookings/init")
			.then()
				.statusCode(422)
				.body("type", equalTo("urn:roomkeeper:problem:idempotency-key-reused"));
		assertThat(reservedOnTheFirstNight()).isEqualTo(2);
	}

	@Test
	void aSoldOutAnswerIsNotKeptSoTheRetryTriesAgain() {
		api(guest).header("Idempotency-Key", "too-many").contentType(ContentType.JSON).body(stay(6))
			.when().post("/api/v1/bookings/init")
			.then().statusCode(409);

		api(guest).header("Idempotency-Key", "too-many").contentType(ContentType.JSON).body(stay(6))
			.when().post("/api/v1/bookings/init")
			.then()
				.statusCode(409)
				.header("Idempotent-Replayed", nullValue());
	}

	@Test
	void keysBelongToOneGuest() {
		init("shared-key").statusCode(201);
		String otherGuest = signUpAndLogIn(aUser().build());

		api(otherGuest).header("Idempotency-Key", "shared-key").contentType(ContentType.JSON).body(stay(2))
			.when().post("/api/v1/bookings/init")
			.then()
				.statusCode(201)
				.header("Idempotent-Replayed", nullValue());
		assertThat(reservedOnTheFirstNight()).isEqualTo(4);
	}

	@Test
	void retriesRacingTheFirstRequestMakeOneBooking() throws Exception {
		List<Callable<Response>> attempts = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			attempts.add(() -> api(guest).header("Idempotency-Key", "double-click").contentType(ContentType.JSON)
				.body(stay(2))
				.post("/api/v1/bookings/init"));
		}

		List<Response> responses = runTogether(attempts);

		// Each one got the booking, or was told the first request is still running
		assertThat(responses).extracting(Response::statusCode).containsOnly(201, 409).contains(201);
		List<Long> bookingIds = responses.stream()
			.filter(response -> response.statusCode() == 201)
			.map(response -> response.jsonPath().getLong("id"))
			.distinct()
			.toList();
		assertThat(bookingIds).hasSize(1);
		assertThat(reservedOnTheFirstNight()).isEqualTo(2);
	}

	private ValidatableResponse init(String idempotencyKey) {
		return api(guest).header("Idempotency-Key", idempotencyKey).contentType(ContentType.JSON).body(stay(2))
			.when().post("/api/v1/bookings/init")
			.then();
	}

	private Map<String, Object> stay(int rooms) {
		return Map.of("roomId", roomId, "checkIn", "2026-03-10", "checkOut", "2026-03-12", "rooms", rooms);
	}

	private int reservedOnTheFirstNight() {
		return jdbc.queryForObject("select reserved_count from inventory where room_id = ? and date = ?",
				Integer.class, roomId, LocalDate.parse("2026-03-10"));
	}

}
