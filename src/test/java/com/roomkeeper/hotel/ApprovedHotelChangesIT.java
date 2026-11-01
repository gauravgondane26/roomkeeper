package com.roomkeeper.hotel;

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
import static org.hamcrest.Matchers.equalTo;

/**
 * An admin approves a hotel as it was described: renamed or moved to another city, it goes
 * back to the admin and off sale until approved again, so an approved listing can't be
 * turned into a different one. Its time zone and amenities can change freely. Bookings it
 * already has carry on (HotelDeactivationIT).
 */
@Import(MutableClockConfig.class)
class ApprovedHotelChangesIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	private String manager;

	private String city;

	private long hotelId;

	@BeforeEach
	void openAnApprovedHotel() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		city = "Approval City " + System.nanoTime();
		hotelId = activeHotel(manager, aHotel().named("Seaside Inn").inCity(city).build(), aRoom().build());
		search(city).body("totalHotels", equalTo(1));
	}

	@Test
	void aRenamedHotelGoesBackToTheAdminAndOffSale() {
		update(Map.of("name", "Grand Palace Hotel", "city", city, "timeZone", "UTC", "amenities", List.of("wifi")))
			.statusCode(200)
			.body("name", equalTo("Grand Palace Hotel"))
			.body("approvalStatus", equalTo("PENDING"))
			.body("active", equalTo(false));

		search(city).body("totalHotels", equalTo(0));
		api().when().get("/api/v1/hotels/{id}/info", hotelId).then().statusCode(404);
		api(manager).when().patch("/api/v1/admin/hotels/{id}/activate", hotelId)
			.then().statusCode(409).body("type", equalTo("urn:roomkeeper:problem:hotel-not-approved"));
	}

	@Test
	void aHotelMovedToAnotherCityGoesBackToTheAdminToo() {
		String elsewhere = "Elsewhere " + System.nanoTime();

		update(Map.of("name", "Seaside Inn", "city", elsewhere, "timeZone", "UTC", "amenities", List.of("wifi")))
			.statusCode(200)
			.body("approvalStatus", equalTo("PENDING"))
			.body("active", equalTo(false));

		search(elsewhere).body("totalHotels", equalTo(0));
	}

	@Test
	void itsTimeZoneAndAmenitiesCanChangeWithoutAReview() {
		update(Map.of("name", "Seaside Inn", "city", city, "timeZone", "Asia/Kolkata", "amenities",
				List.of("wifi", "spa")))
			.statusCode(200)
			.body("approvalStatus", equalTo("APPROVED"))
			.body("active", equalTo(true));

		search(city).body("totalHotels", equalTo(1));
	}

	@Test
	void onceApprovedAgainItCanGoBackOnSale() {
		update(Map.of("name", "Grand Palace Hotel", "city", city, "timeZone", "UTC", "amenities", List.of("wifi")))
			.statusCode(200);

		activateHotel(manager, hotelId);

		search(city).body("totalHotels", equalTo(1)).body("hotels[0].name", equalTo("Grand Palace Hotel"));
	}

	private ValidatableResponse update(Map<String, Object> hotel) {
		return api(manager).contentType(ContentType.JSON).body(hotel)
			.when().put("/api/v1/admin/hotels/{id}", hotelId)
			.then();
	}

	private ValidatableResponse search(String inCity) {
		return api().queryParam("city", inCity).queryParam("checkIn", "2026-03-10").queryParam("checkOut", "2026-03-12")
			.when().get("/api/v1/hotels/search")
			.then().statusCode(200);
	}

}
