package com.roomkeeper.hotel;

import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

class HotelAdminIT extends AbstractIntegrationTest {

	private String manager;

	@BeforeEach
	void signInAsAManager() {
		manager = signUpAndLogIn(aUser().asHotelManager().build());
	}

	@Test
	void managerCreatesAnInactiveHotel() {
		var response = api(manager).contentType(ContentType.JSON)
			.body(aHotel().named("  Seaside Inn ").inCity("Goa").withAmenities("wifi", " pool ", "wifi").build())
			.when().post("/api/v1/admin/hotels")
			.then()
				.statusCode(201)
				.body("name", equalTo("Seaside Inn"))
				.body("city", equalTo("Goa"))
				.body("amenities", contains("wifi", "pool"))
				.body("active", equalTo(false))
				.extract();

		long id = response.jsonPath().getLong("id");
		assertThat(response.header("Location")).endsWith("/api/v1/admin/hotels/" + id);
	}

	@Test
	void amenitiesAreOptional() {
		Map<String, Object> hotel = aHotel().build();
		hotel.remove("amenities");

		api(manager).contentType(ContentType.JSON).body(hotel)
			.when().post("/api/v1/admin/hotels")
			.then()
				.statusCode(201)
				.body("amenities", empty());
	}

	@Test
	void rejectsAHotelWithoutANameOrCity() {
		api(manager).contentType(ContentType.JSON).body(Map.of("name", " ", "city", ""))
			.when().post("/api/v1/admin/hotels")
			.then()
				.statusCode(400)
				.body("type", equalTo("urn:roomkeeper:problem:validation"))
				.body("errors.field", hasItem("name"))
				.body("errors.field", hasItem("city"));
	}

	@Test
	void listsOnlyTheCallersHotels() {
		createHotel(manager, aHotel().named("Mine").build());
		String otherManager = signUpAndLogIn(aUser().asHotelManager().build());
		createHotel(otherManager, aHotel().named("Theirs").build());

		api(manager)
			.when().get("/api/v1/admin/hotels")
			.then()
				.statusCode(200)
				.body("name", contains("Mine"));
	}

	@Test
	void ownerReadsUpdatesAndDeletesTheirHotel() {
		long id = createHotel(manager, aHotel().named("Old Name").build());

		api(manager)
			.when().get("/api/v1/admin/hotels/{id}", id)
			.then()
				.statusCode(200)
				.body("name", equalTo("Old Name"));

		api(manager).contentType(ContentType.JSON)
			.body(aHotel().named("New Name").inCity("Pune").withAmenities("gym").build())
			.when().put("/api/v1/admin/hotels/{id}", id)
			.then()
				.statusCode(200)
				.body("name", equalTo("New Name"))
				.body("city", equalTo("Pune"))
				.body("amenities", contains("gym"));

		api(manager)
			.when().delete("/api/v1/admin/hotels/{id}", id)
			.then()
				.statusCode(204);

		api(manager)
			.when().get("/api/v1/admin/hotels/{id}", id)
			.then()
				.statusCode(404)
				.body("type", equalTo("urn:roomkeeper:problem:not-found"));
	}

	@Test
	void anotherManagerCannotReadChangeOrDeleteTheHotel() {
		long id = createHotel(manager, aHotel().named("Seaside Inn").build());
		String otherManager = signUpAndLogIn(aUser().asHotelManager().build());

		api(otherManager)
			.when().get("/api/v1/admin/hotels/{id}", id)
			.then()
				.statusCode(403)
				.body("type", equalTo("urn:roomkeeper:problem:forbidden"));
		api(otherManager).contentType(ContentType.JSON).body(aHotel().named("Hijacked").build())
			.when().put("/api/v1/admin/hotels/{id}", id)
			.then()
				.statusCode(403);
		api(otherManager)
			.when().delete("/api/v1/admin/hotels/{id}", id)
			.then()
				.statusCode(403);

		api(manager)
			.when().get("/api/v1/admin/hotels/{id}", id)
			.then()
				.statusCode(200)
				.body("name", equalTo("Seaside Inn"));
	}

	@Test
	void unknownHotelIsNotFound() {
		api(manager)
			.when().get("/api/v1/admin/hotels/{id}", Long.MAX_VALUE)
			.then()
				.statusCode(404)
				.body("type", equalTo("urn:roomkeeper:problem:not-found"));
	}

	@Test
	void guestsCannotManageHotels() {
		String guest = signUpAndLogIn(aUser().build());

		api(guest).contentType(ContentType.JSON).body(aHotel().build())
			.when().post("/api/v1/admin/hotels")
			.then()
				.statusCode(403)
				.body("type", equalTo("urn:roomkeeper:problem:forbidden"));
	}

}
