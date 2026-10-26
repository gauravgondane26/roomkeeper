package com.roomkeeper.booking;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

/** A guest reads their bookings: one at a time, its status, and all of them newest first. */
@Import(MutableClockConfig.class)
class BookingQueriesIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	private String manager;

	private String guest;

	private long hotelId;

	private long roomId;

	@BeforeEach
	void openAHotel() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		guest = signUpAndLogIn(aUser().build());
		hotelId = activeHotel(manager, aHotel().build(), aRoom().ofType("Deluxe").pricedAt("4000").build());
		roomId = firstRoomOf(manager, hotelId);
	}

	@Test
	void readsTheStatusOfAHold() {
		long id = hold("2026-03-10", "2026-03-12");

		api(guest).when().get("/api/v1/bookings/{id}/status", id)
			.then().statusCode(200)
			.body("id", equalTo((int) id))
			.body("status", equalTo("RESERVED"))
			.body("holdExpiresAt", equalTo("2026-03-10T09:10:00Z"));
	}

	@Test
	void aHoldReadsExpiredOnceItRunsOutEvenBeforeTheJobReleasesIt() {
		long id = hold("2026-03-10", "2026-03-12");

		clock.advance(Duration.ofMinutes(10));

		api(guest).when().get("/api/v1/bookings/{id}/status", id).then().body("status", equalTo("EXPIRED"));
		api(guest).when().get("/api/v1/bookings/{id}", id).then().body("status", equalTo("EXPIRED"));
	}

	@Test
	void readsTheWholeBookingAtTheAmountFrozenWhenHeld() {
		long id = hold("2026-03-10", "2026-03-12");
		// The manager raises the price afterwards
		api(manager).contentType(ContentType.JSON).body(aRoom().ofType("Deluxe").pricedAt("9000").build())
			.when().put("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, roomId)
			.then().statusCode(200);

		api(guest).when().get("/api/v1/bookings/{id}", id)
			.then().statusCode(200)
			.body("roomType", equalTo("Deluxe"))
			.body("checkIn", equalTo("2026-03-10"))
			.body("amount", equalTo(new BigDecimal("8000.00")))
			.body("guests", empty());
	}

	@Test
	void anotherUsersBookingIsNotFound() {
		long id = hold("2026-03-10", "2026-03-12");
		String other = signUpAndLogIn(aUser().build());

		api(other).when().get("/api/v1/bookings/{id}", id).then().statusCode(404);
		api(other).when().get("/api/v1/bookings/{id}/status", id).then().statusCode(404);
	}

	@Test
	void listsTheUsersBookingsNewestFirstAPageAtATime() {
		long first = hold("2026-03-10", "2026-03-11");
		clock.advance(Duration.ofMinutes(1));
		long second = hold("2026-03-12", "2026-03-13");
		clock.advance(Duration.ofMinutes(1));
		long third = hold("2026-03-14", "2026-03-15");
		hold(signUpAndLogIn(aUser().build()), "2026-03-16", "2026-03-17");

		api(guest).queryParam("size", 2).when().get("/api/v1/users/bookings")
			.then().statusCode(200)
			.body("bookings.id", contains((int) third, (int) second))
			.body("page", equalTo(0))
			.body("size", equalTo(2))
			.body("totalBookings", equalTo(3))
			.body("totalPages", equalTo(2));
		api(guest).queryParam("size", 2).queryParam("page", 1).when().get("/api/v1/users/bookings")
			.then().body("bookings.id", contains((int) first));
	}

	@Test
	void rejectsAPageSizeOverFifty() {
		api(guest).queryParam("size", 51).when().get("/api/v1/users/bookings")
			.then().statusCode(400).body("type", equalTo("urn:roomkeeper:problem:validation"));
	}

	private long hold(String checkIn, String checkOut) {
		return hold(guest, checkIn, checkOut);
	}

	private long hold(String token, String checkIn, String checkOut) {
		return api(token).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", checkIn, "checkOut", checkOut, "rooms", 1))
			.when().post("/api/v1/bookings/init")
			.then().statusCode(201).extract().jsonPath().getLong("id");
	}

}
