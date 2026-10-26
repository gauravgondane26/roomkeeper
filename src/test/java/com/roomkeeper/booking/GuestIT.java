package com.roomkeeper.booking;

import java.util.LinkedHashMap;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

/**
 * Saved guests: the travellers a user keeps on file to put on bookings. Each user only
 * sees their own.
 */
class GuestIT extends AbstractIntegrationTest {

	private static final String GUESTS = "/api/v1/users/guests";

	private String user;

	@BeforeEach
	void logIn() {
		user = signUpAndLogIn(aUser().build());
	}

	@Test
	void savesAGuest() {
		ExtractableResponse<Response> response = create(guest(" Asha Rao ", 34, "FEMALE"))
			.statusCode(201)
			.body("name", equalTo("Asha Rao"))
			.body("age", equalTo(34))
			.body("gender", equalTo("FEMALE"))
			.extract();

		long id = response.jsonPath().getLong("id");
		assertThat(response.header("Location")).endsWith(GUESTS + "/" + id);
		api(user).when().get(GUESTS + "/{id}", id).then().statusCode(200).body("name", equalTo("Asha Rao"));
	}

	@Test
	void listsTheUsersGuestsInTheOrderSaved() {
		create(guest("Asha Rao", 34, "FEMALE"));
		create(guest("Vikram Rao", 36, "MALE"));

		api(user).when().get(GUESTS).then().statusCode(200).body("name", contains("Asha Rao", "Vikram Rao"));
	}

	@Test
	void updatesAGuest() {
		long id = idOf(create(guest("Asha Rao", 34, "FEMALE")));

		api(user).contentType(ContentType.JSON).body(guest("Asha Menon", 35, "FEMALE"))
			.when().put(GUESTS + "/{id}", id)
			.then().statusCode(200).body("name", equalTo("Asha Menon")).body("age", equalTo(35));
	}

	@Test
	void removesAGuest() {
		long id = idOf(create(guest("Asha Rao", 34, "FEMALE")));

		api(user).when().delete(GUESTS + "/{id}", id).then().statusCode(204);

		api(user).when().get(GUESTS).then().body("$", empty());
		api(user).when().get(GUESTS + "/{id}", id).then().statusCode(404);
		api(user).when().delete(GUESTS + "/{id}", id).then().statusCode(404);
	}

	@Test
	void anotherUsersGuestsAreNotFound() {
		long id = idOf(create(guest("Asha Rao", 34, "FEMALE")));
		String other = signUpAndLogIn(aUser().build());

		api(other).when().get(GUESTS).then().body("$", empty());
		api(other).when().get(GUESTS + "/{id}", id).then().statusCode(404);
		api(other).contentType(ContentType.JSON).body(guest("Taken", 30, "OTHER"))
			.when().put(GUESTS + "/{id}", id).then().statusCode(404);
		api(other).when().delete(GUESTS + "/{id}", id).then().statusCode(404);
	}

	@Test
	void validatesTheGuest() {
		create(guest(" ", 34, "FEMALE")).statusCode(400).body("errors[0].field", equalTo("name"));
		create(guest("Asha", -1, "FEMALE")).statusCode(400).body("errors[0].field", equalTo("age"));
		create(guest("Asha", 151, "FEMALE")).statusCode(400).body("errors[0].field", equalTo("age"));
		create(guest("Asha", 34, null)).statusCode(400).body("errors[0].field", equalTo("gender"));
	}

	@Test
	void requiresALogIn() {
		api().when().get(GUESTS).then().statusCode(401);
	}

	private ValidatableResponse create(Map<String, Object> guest) {
		return api(user).contentType(ContentType.JSON).body(guest).when().post(GUESTS).then();
	}

	private static long idOf(ValidatableResponse response) {
		return response.statusCode(201).extract().jsonPath().getLong("id");
	}

	private static Map<String, Object> guest(String name, int age, String gender) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("age", age);
		body.put("gender", gender);
		return body;
	}

}
