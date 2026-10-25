package com.roomkeeper.auth;

import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.TestUser;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

class SignupIT extends AbstractIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void createsAGuestAndStoresOnlyAPasswordHash() {
		TestUser user = aUser().build();

		signUp(user.signupRequest())
			.statusCode(201)
			.body("id", notNullValue())
			.body("email", equalTo(user.email()))
			.body("name", equalTo(user.name()))
			.body("role", equalTo("GUEST"))
			.body("$", not(hasKey("password")))
			.body("$", not(hasKey("passwordHash")));

		String hash = jdbc.queryForObject("select password_hash from users where email = ?", String.class,
				user.email());
		assertThat(hash).startsWith("{bcrypt}").doesNotContain(user.password());
	}

	@Test
	void canSignUpAsHotelManager() {
		signUp(aUser().asHotelManager().build().signupRequest())
			.statusCode(201)
			.body("role", equalTo("HOTEL_MANAGER"));
	}

	@Test
	void storesEmailInLowerCase() {
		String mixedCase = "Mixed.Case-" + System.nanoTime() + "@Example.COM";

		signUp(aUser().withEmail(mixedCase).build().signupRequest())
			.statusCode(201)
			.body("email", equalTo(mixedCase.toLowerCase()));
	}

	@Test
	void rejectsAnEmailAlreadyRegisteredIgnoringCase() {
		TestUser user = aUser().build();
		signUp(user.signupRequest()).statusCode(201);

		signUp(aUser().withEmail(user.email().toUpperCase()).build().signupRequest())
			.statusCode(409)
			.body("type", equalTo("urn:roomkeeper:problem:email-already-registered"));
	}

	@Test
	void rejectsInvalidFieldsWithAValidationProblem() {
		signUp(Map.of("email", "not-an-email", "password", "short", "name", ""))
			.statusCode(400)
			.body("type", equalTo("urn:roomkeeper:problem:validation"))
			.body("errors.field", containsInAnyOrder("email", "password", "name"));
	}

	@Test
	void rejectsAnUnknownRole() {
		var body = new java.util.HashMap<>(aUser().build().signupRequest());
		body.put("role", "ADMIN");

		signUp(body)
			.statusCode(400)
			.body("type", equalTo("urn:roomkeeper:problem:bad-request"));
	}

	private ValidatableResponse signUp(Map<String, Object> body) {
		return api()
			.contentType(ContentType.JSON)
			.body(body)
			.when().post("/api/v1/auth/signup")
			.then();
	}

}
