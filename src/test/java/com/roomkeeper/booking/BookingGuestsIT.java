package com.roomkeeper.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

/**
 * POST /bookings/{id}/guests puts saved guests on a booking while its rooms are held, as
 * many as the rooms sleep. The hold's clock keeps running.
 */
@Import(MutableClockConfig.class)
class BookingGuestsIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	private String guest;

	private long bookingId;

	private long asha;

	private long vikram;

	@BeforeEach
	void holdOneRoomSleepingTwo() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		guest = signUpAndLogIn(aUser().build());
		long hotelId = activeHotel(manager, aHotel().build(), aRoom().sleeping(2).build());
		bookingId = api(guest).contentType(ContentType.JSON)
			.body(Map.of("roomId", firstRoomOf(manager, hotelId), "checkIn", "2026-03-10", "checkOut", "2026-03-12",
					"rooms", 1))
			.when().post("/api/v1/bookings/init")
			.then().statusCode(201).extract().jsonPath().getLong("id");
		asha = saveGuest(guest, "Asha Rao");
		vikram = saveGuest(guest, "Vikram Rao");
	}

	@Test
	void putsTheGuestsOnTheBooking() {
		putGuests(guest, List.of(vikram, asha))
			.statusCode(200)
			.body("status", equalTo("GUESTS_ADDED"))
			.body("guests.name", contains("Asha Rao", "Vikram Rao"));
	}

	@Test
	void replacesTheGuestsWhileTheRoomsAreHeld() {
		putGuests(guest, List.of(asha, vikram)).statusCode(200);

		putGuests(guest, List.of(vikram)).statusCode(200).body("guests.name", contains("Vikram Rao"));
	}

	@Test
	void takesNoMoreGuestsThanTheRoomsSleep() {
		long third = saveGuest(guest, "Meera Rao");

		putGuests(guest, List.of(asha, vikram, third))
			.statusCode(400)
			.body("type", equalTo("urn:roomkeeper:problem:validation"))
			.body("errors[0].field", equalTo("guestIds"));
	}

	@Test
	void namingAGuestTwiceCountsOnce() {
		putGuests(guest, List.of(asha, asha, vikram))
			.statusCode(200)
			.body("guests.name", contains("Asha Rao", "Vikram Rao"));
	}

	@Test
	void refusesAfterTheHoldRunsOut() {
		// Still inside the access token's 15 minutes
		clock.advance(Duration.ofMinutes(10));

		putGuests(guest, List.of(asha))
			.statusCode(409)
			.body("type", equalTo("urn:roomkeeper:problem:hold-expired"));
	}

	@Test
	void onlyTheUsersOwnGuestsCanBeAdded() {
		String other = signUpAndLogIn(aUser().build());
		long theirs = saveGuest(other, "Someone Else");

		putGuests(guest, List.of(asha, theirs)).statusCode(404);
	}

	@Test
	void removedGuestsCantBeAdded() {
		api(guest).when().delete("/api/v1/users/guests/{id}", asha).then().statusCode(204);

		putGuests(guest, List.of(asha)).statusCode(404);
	}

	@Test
	void anotherUsersBookingIsNotFound() {
		String other = signUpAndLogIn(aUser().build());

		putGuests(other, List.of(saveGuest(other, "Someone Else"))).statusCode(404);
	}

	@Test
	void needsAtLeastOneGuest() {
		putGuests(guest, List.of()).statusCode(400).body("errors[0].field", equalTo("guestIds"));
	}

	private ValidatableResponse putGuests(String token, List<Long> guestIds) {
		return api(token).contentType(ContentType.JSON).body(Map.of("guestIds", guestIds))
			.when().post("/api/v1/bookings/{id}/guests", bookingId)
			.then();
	}

	private long saveGuest(String token, String name) {
		return api(token).contentType(ContentType.JSON).body(Map.of("name", name, "age", 30, "gender", "OTHER"))
			.when().post("/api/v1/users/guests")
			.then().statusCode(201).extract().jsonPath().getLong("id");
	}

}
