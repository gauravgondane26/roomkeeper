package com.roomkeeper.auth;

import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.TestUser;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

class LoginIT extends AbstractIntegrationTest {

	@Test
	void returnsABearerAccessTokenForValidCredentials() {
		TestUser user = aUser().build();
		signUp(user);

		logIn(user.email(), user.password())
			.statusCode(200)
			.body("tokenType", equalTo("Bearer"))
			.body("expiresIn", equalTo(900))
			.body("accessToken", matchesPattern("[\\w-]+\\.[\\w-]+\\.[\\w-]+"));
	}

	@Test
	void emailIsCaseInsensitive() {
		TestUser user = aUser().build();
		signUp(user);

		logIn(user.email().toUpperCase(), user.password()).statusCode(200);
	}

	@Test
	void wrongPasswordIsAnInvalidCredentialsProblem() {
		TestUser user = aUser().build();
		signUp(user);

		logIn(user.email(), "not-the-password")
			.statusCode(401)
			.body("type", equalTo("urn:roomkeeper:problem:invalid-credentials"));
	}

	@Test
	void unknownEmailGetsExactlyTheSameAnswerAsAWrongPassword() {
		TestUser user = aUser().build();
		signUp(user);

		String wrongPassword = logIn(user.email(), "not-the-password").statusCode(401).extract().asString();
		String unknownEmail = logIn("nobody-" + user.email(), "not-the-password").statusCode(401).extract().asString();

		assertThat(unknownEmail).isEqualTo(wrongPassword);
	}

	@Test
	void missingFieldsAreAValidationProblem() {
		api().contentType(ContentType.JSON).body(Map.of())
			.when().post("/api/v1/auth/login")
			.then()
				.statusCode(400)
				.body("type", equalTo("urn:roomkeeper:problem:validation"))
				.body("errors.field", containsInAnyOrder("email", "password"));
	}

	private ValidatableResponse logIn(String email, String password) {
		return api().contentType(ContentType.JSON).body(Map.of("email", email, "password", password))
			.when().post("/api/v1/auth/login")
			.then();
	}

}
