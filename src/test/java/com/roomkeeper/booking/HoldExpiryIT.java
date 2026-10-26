package com.roomkeeper.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.restassured.http.ContentType;
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
import static org.hamcrest.Matchers.equalTo;

/**
 * Signature test: an unpaid hold gives its rooms back after 10 minutes. The clock is
 * moved instead of waited on, and the job is run directly instead of on its schedule.
 */
@Import(MutableClockConfig.class)
class HoldExpiryIT extends AbstractIntegrationTest {

	private static final Instant HELD_AT = Instant.parse("2026-03-10T09:00:00Z");

	@Autowired
	MutableClock clock;

	@Autowired
	HoldExpiryJob job;

	@Autowired
	JdbcTemplate jdbc;

	private String manager;

	private long roomId;

	@BeforeEach
	void openAHotelWithOneRoom() {
		clock.setInstant(HELD_AT);
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		roomId = firstRoomOf(manager, activeHotel(manager, aHotel().build(), aRoom().withTotalCount(1).build()));
	}

	@Test
	void aHoldExpiresAfterTenMinutes() {
		String guest = signUpAndLogIn(aUser().build());
		long bookingId = hold(guest).statusCode(201).extract().jsonPath().getLong("id");

		clock.setInstant(HELD_AT.plus(Duration.ofMinutes(10)).minusSeconds(1));
		job.run();

		assertThat(storedStatus(bookingId)).as("a second before the hold runs out").isEqualTo("RESERVED");
		assertThat(reservedOnTuesday()).isEqualTo(1);

		clock.setInstant(HELD_AT.plus(Duration.ofMinutes(10)));
		job.run();

		assertThat(storedStatus(bookingId)).isEqualTo("EXPIRED");
		assertThat(reservedOnTuesday()).isZero();
		api(guest).when().get("/api/v1/bookings/{id}/status", bookingId).then().body("status", equalTo("EXPIRED"));
		// The room is back on sale
		hold(signUpAndLogIn(aUser().build())).statusCode(201);
	}

	private ValidatableResponse hold(String guest) {
		return api(guest).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", "2026-03-10", "checkOut", "2026-03-12", "rooms", 1))
			.when().post("/api/v1/bookings/init")
			.then();
	}

	/** The status recorded in the database, not the one the API works out from the clock. */
	private String storedStatus(long bookingId) {
		return jdbc.queryForObject("select status from bookings where id = ?", String.class, bookingId);
	}

	private int reservedOnTuesday() {
		return jdbc.queryForObject("select reserved_count from inventory where room_id = ? and date = '2026-03-10'",
				Integer.class, roomId);
	}

}
