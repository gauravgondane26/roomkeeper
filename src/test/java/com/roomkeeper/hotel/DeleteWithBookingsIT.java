package com.roomkeeper.hotel;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.roomkeeper.booking.HoldExpiryJob;
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
import static org.hamcrest.Matchers.equalTo;

/**
 * Bookings are history a hotel keeps: a hotel or room type that has any, even expired
 * ones, can't be deleted. Taking it off sale is the way out.
 */
@Import(MutableClockConfig.class)
class DeleteWithBookingsIT extends AbstractIntegrationTest {

	private static final String HAS_BOOKINGS = "urn:roomkeeper:problem:has-bookings";

	@Autowired
	MutableClock clock;

	@Autowired
	HoldExpiryJob expiry;

	private String manager;

	private long hotelId;

	private long roomId;

	@BeforeEach
	void openAHotel() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		hotelId = activeHotel(manager, aHotel().build(), aRoom().ofType("Deluxe").build());
		roomId = firstRoomOf(manager, hotelId);
	}

	@Test
	void aRoomTypeWithBookingsCantBeDeleted() {
		book();

		api(manager).when().delete("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, roomId)
			.then().statusCode(409).body("type", equalTo(HAS_BOOKINGS));
		api(manager).when().get("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, roomId)
			.then().statusCode(200);
	}

	@Test
	void aHotelWithBookingsCantBeDeleted() {
		book();

		api(manager).when().delete("/api/v1/admin/hotels/{id}", hotelId)
			.then().statusCode(409).body("type", equalTo(HAS_BOOKINGS));
		api(manager).when().get("/api/v1/admin/hotels/{id}", hotelId).then().statusCode(200);
	}

	@Test
	void expiredBookingsCountToo() {
		book();
		clock.advance(Duration.ofMinutes(10));
		expiry.run();

		api(manager).when().delete("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, roomId)
			.then().statusCode(409).body("type", equalTo(HAS_BOOKINGS));
	}

	@Test
	void anotherRoomTypeWithoutBookingsCanStillBeDeleted() {
		long suite = createRoom(manager, hotelId, aRoom().ofType("Suite").build());
		book();

		api(manager).when().delete("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, suite)
			.then().statusCode(204);
	}

	private void book() {
		api(signUpAndLogIn(aUser().build())).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", "2026-03-10", "checkOut", "2026-03-11", "rooms", 1))
			.when().post("/api/v1/bookings/init")
			.then().statusCode(201);
	}

}
