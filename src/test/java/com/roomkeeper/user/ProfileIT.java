package com.roomkeeper.user;

import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.TestUser;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

class ProfileIT extends AbstractIntegrationTest {

	@Test
	void returnsTheCallersProfile() {
		TestUser user = aUser().withName("Asha Rao").build();
		long id = signUp(user);
		String token = logIn(user);

		api(token)
			.when().get("/api/v1/users/profile")
			.then()
				.statusCode(200)
				.body("id", equalTo((int) id))
				.body("email", equalTo(user.email()))
				.body("name", equalTo("Asha Rao"))
				.body("role", equalTo("GUEST"));
	}

	@Test
	void requiresAuthentication() {
		api()
			.when().get("/api/v1/users/profile")
			.then()
				.statusCode(401);
	}

	@Test
	void updatesTheName() {
		String token = signUpAndLogIn(aUser().withName("Old Name").build());

		api(token).contentType(ContentType.JSON).body(Map.of("name", "  New Name  "))
			.when().patch("/api/v1/users/profile")
			.then()
				.statusCode(200)
				.body("name", equalTo("New Name"));

		api(token)
			.when().get("/api/v1/users/profile")
			.then()
				.body("name", equalTo("New Name"));
	}

	@Test
	void rejectsABlankName() {
		String token = signUpAndLogIn(aUser().build());

		api(token).contentType(ContentType.JSON).body(Map.of("name", "   "))
			.when().patch("/api/v1/users/profile")
			.then()
				.statusCode(400)
				.body("type", equalTo("urn:roomkeeper:problem:validation"))
				.body("errors.field", contains("name"));
	}

	@Test
	void cannotChangeItsOwnRoleOrEmail() {
		TestUser user = aUser().build();
		String token = signUpAndLogIn(user);

		api(token).contentType(ContentType.JSON)
			.body(Map.of("role", "HOTEL_MANAGER", "email", "someone-else@example.com"))
			.when().patch("/api/v1/users/profile")
			.then()
				.statusCode(200)
				.body("role", equalTo("GUEST"))
				.body("email", equalTo(user.email()));
	}

}
