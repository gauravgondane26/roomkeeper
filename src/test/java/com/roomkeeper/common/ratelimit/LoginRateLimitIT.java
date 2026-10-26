package com.roomkeeper.common.ratelimit;

import java.time.Duration;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import com.roomkeeper.testsupport.TestUser;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.hamcrest.Matchers.equalTo;

/**
 * Signature test: login is limited to 10 attempts per minute per client, so password
 * guessing is slow. Time is controlled with a MutableClock instead of sleeping.
 */
@Import(MutableClockConfig.class)
class LoginRateLimitIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	@Test
	void eleventhLoginAttemptInsideTheWindowIsRejectedWith429() {
		TestUser user = aUser().build();
		signUp(user);

		for (int attempt = 1; attempt <= 10; attempt++) {
			logIn(user.email(), "wrong-password").statusCode(401);
		}

		// Even the right password: the limit is on attempts, not failures
		logIn(user.email(), user.password())
			.statusCode(429)
			.header("Retry-After", equalTo("6"))
			.body("type", equalTo("urn:roomkeeper:problem:rate-limited"));
	}

	@Test
	void attemptsAreAllowedAgainAsTheBucketRefills() {
		TestUser user = aUser().build();
		signUp(user);
		for (int attempt = 1; attempt <= 10; attempt++) {
			logIn(user.email(), "wrong-password").statusCode(401);
		}
		logIn(user.email(), user.password()).statusCode(429);

		// 10 per minute refills one attempt every 6 seconds
		clock.advance(Duration.ofSeconds(6));

		logIn(user.email(), user.password()).statusCode(200);
		logIn(user.email(), user.password()).statusCode(429);
	}

	@Test
	void otherRoutesAreNotLimitedByTheLoginLimit() {
		TestUser user = aUser().build();
		for (int attempt = 1; attempt <= 11; attempt++) {
			logIn(user.email(), "wrong-password");
		}

		api().contentType(ContentType.JSON).body(user.signupRequest())
			.when().post("/api/v1/auth/signup")
			.then().statusCode(201);
	}

	private ValidatableResponse logIn(String email, String password) {
		return api().contentType(ContentType.JSON).body(Map.of("email", email, "password", password))
			.when().post("/api/v1/auth/login")
			.then();
	}

}
