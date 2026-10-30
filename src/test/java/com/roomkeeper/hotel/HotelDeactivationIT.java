package com.roomkeeper.hotel;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.roomkeeper.booking.HoldExpiryJob;
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
import static org.hamcrest.Matchers.equalTo;

/**
 * Taking a hotel off sale: guests can't find or book it any more, but bookings it already
 * has carry on, and its history stays. Its manager can put it back on sale.
 */
@Import(MutableClockConfig.class)
class HotelDeactivationIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	@Autowired
	HoldExpiryJob expiry;

	private String manager;

	private String guest;

	private String city;

	private long hotelId;

	private long roomId;

	@BeforeEach
	void openAHotel() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		guest = signUpAndLogIn(aUser().build());
		city = "OffSale-" + UUID.randomUUID();
		hotelId = activeHotel(manager, aHotel().inCity(city).build(), aRoom().build());
		roomId = firstRoomOf(manager, hotelId);
	}

	@Test
	void guestsCantFindOrBookAHotelTakenOffSale() {
		search().body("totalHotels", equalTo(1));
		api().when().get("/api/v1/hotels/{id}/info", hotelId).then().statusCode(200);
		awaitCached("hotel:*:" + hotelId + ":v*");

		deactivate(manager).statusCode(200).body("active", equalTo(false));

		search().body("totalHotels", equalTo(0));
		api().when().get("/api/v1/hotels/{id}/info", hotelId).then().statusCode(404);
		hold().statusCode(404);
	}

	@Test
	void bookingsItAlreadyHasCarryOn() {
		long bookingId = hold().statusCode(201).extract().jsonPath().getLong("id");
		long asha = api(guest).contentType(ContentType.JSON).body(Map.of("name", "Asha", "age", 30, "gender", "FEMALE"))
			.when().post("/api/v1/users/guests").then().statusCode(201).extract().jsonPath().getLong("id");

		deactivate(manager).statusCode(200);

		api(guest).contentType(ContentType.JSON).body(Map.of("guestIds", List.of(asha)))
			.when().post("/api/v1/bookings/{id}/guests", bookingId)
			.then().statusCode(200).body("status", equalTo("GUESTS_ADDED"));
		api(guest).when().get("/api/v1/bookings/{id}", bookingId).then().statusCode(200);
	}

	@Test
	void itsHoldsStillExpire() {
		long bookingId = hold().statusCode(201).extract().jsonPath().getLong("id");
		deactivate(manager).statusCode(200);

		clock.advance(Duration.ofMinutes(10));
		expiry.run();

		api(guest).when().get("/api/v1/bookings/{id}/status", bookingId).then().body("status", equalTo("EXPIRED"));
		nightsOf(manager, roomId, "2026-03-10", "2026-03-10").body("[0].reservedCount", equalTo(0));
	}

	@Test
	void theManagerCanPutItBackOnSale() {
		deactivate(manager).statusCode(200);
		search().body("totalHotels", equalTo(0));

		api(manager).when().patch("/api/v1/admin/hotels/{id}/activate", hotelId).then().statusCode(200);

		search().body("totalHotels", equalTo(1));
		hold().statusCode(201);
	}

	@Test
	void takingItOffSaleTwiceChangesNothing() {
		deactivate(manager).statusCode(200);

		deactivate(manager).statusCode(200).body("active", equalTo(false));
	}

	@Test
	void onlyItsManagerCanTakeItOffSale() {
		deactivate(signUpAndLogIn(aUser().asHotelManager().build())).statusCode(403);
		deactivate(guest).statusCode(403);
	}

	private ValidatableResponse deactivate(String token) {
		return api(token).when().patch("/api/v1/admin/hotels/{id}/deactivate", hotelId).then();
	}

	private ValidatableResponse hold() {
		return idempotentApi(guest).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", "2026-03-10", "checkOut", "2026-03-12", "rooms", 1))
			.when().post("/api/v1/bookings/init")
			.then();
	}

	private ValidatableResponse search() {
		return api().queryParam("city", city).queryParam("checkIn", "2026-03-10").queryParam("checkOut", "2026-03-12")
			.when().get("/api/v1/hotels/search")
			.then().statusCode(200);
	}

}
