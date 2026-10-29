package com.roomkeeper.booking;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/** A hotel's manager sees its bookings, newest first; nobody else does. */
@Import(MutableClockConfig.class)
class HotelBookingsIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	private String manager;

	private long hotelId;

	private long roomId;

	@BeforeEach
	void openAHotel() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		hotelId = activeHotel(manager, aHotel().build(), aRoom().ofType("Deluxe").withTotalCount(5).build());
		roomId = firstRoomOf(manager, hotelId);
	}

	@Test
	void theManagerSeesTheHotelsBookingsNewestFirst() {
		long first = hold(signUpAndLogIn(aUser().build()), "2026-03-12", 1);
		clock.advance(Duration.ofMinutes(1));
		String secondGuest = signUpAndLogIn(aUser().build());
		long second = hold(secondGuest, "2026-03-15", 2);
		putAGuestOn(secondGuest, second);
		// Another hotel's booking isn't listed
		String otherManager = signUpAndLogIn(aUser().asHotelManager().build());
		long otherRoom = firstRoomOf(otherManager, activeHotel(otherManager, aHotel().build(), aRoom().build()));
		holdIn(otherRoom, signUpAndLogIn(aUser().build()), "2026-03-12", 1);

		bookings(manager, Map.of())
			.statusCode(200)
			.body("bookings.id", contains((int) second, (int) first))
			.body("bookings[0].roomType", equalTo("Deluxe"))
			.body("bookings[0].rooms", equalTo(2))
			.body("bookings[0].status", equalTo("GUESTS_ADDED"))
			.body("bookings[0].guests[0].name", equalTo("Asha"))
			.body("totalBookings", equalTo(2));
	}

	@Test
	void comesAPageAtATime() {
		long first = hold(signUpAndLogIn(aUser().build()), "2026-03-12", 1);
		clock.advance(Duration.ofMinutes(1));
		hold(signUpAndLogIn(aUser().build()), "2026-03-13", 1);

		bookings(manager, Map.of("page", 1, "size", 1))
			.statusCode(200)
			.body("bookings.id", contains((int) first))
			.body("page", equalTo(1))
			.body("totalPages", equalTo(2));
	}

	@Test
	void onlyItsManagerSeesThem() {
		bookings(signUpAndLogIn(aUser().asHotelManager().build()), Map.of()).statusCode(403);
		bookings(signUpAndLogIn(aUser().build()), Map.of()).statusCode(403);
		api(manager).when().get("/api/v1/admin/hotels/{id}/bookings", Long.MAX_VALUE).then().statusCode(404);
	}

	private ValidatableResponse bookings(String token, Map<String, ?> query) {
		return api(token).queryParams(query).when().get("/api/v1/admin/hotels/{id}/bookings", hotelId).then();
	}

	private long hold(String guest, String checkIn, int rooms) {
		return holdIn(roomId, guest, checkIn, rooms);
	}

	private long holdIn(long room, String guest, String checkIn, int rooms) {
		return idempotentApi(guest).contentType(ContentType.JSON)
			.body(Map.of("roomId", room, "checkIn", checkIn,
					"checkOut", LocalDate.parse(checkIn).plusDays(1).toString(), "rooms", rooms))
			.when().post("/api/v1/bookings/init")
			.then().statusCode(201).extract().jsonPath().getLong("id");
	}

}
