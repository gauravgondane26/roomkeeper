package com.roomkeeper.common.ratelimit;

import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.TestUser;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Signup and refresh are rate limited per client IP like login, each with its own bucket:
 * signup so it can't be hammered (it no longer says whether an email is registered),
 * refresh so it can't be hammered.
 */
class AuthRateLimitIT extends AbstractIntegrationTest {

	@Test
	void the21stSignupInAnHourIsRejectedWith429() {
		for (int i = 1; i <= 20; i++) {
			postSignup(aUser().build()).statusCode(202);
		}

		postSignup(aUser().build())
			.statusCode(429)
			.header("Retry-After", notNullValue())
			.body("type", equalTo("urn:roomkeeper:problem:rate-limited"));
	}

	@Test
	void the31stRefreshInAMinuteIsRejectedWith429() {
		for (int i = 1; i <= 30; i++) {
			refresh("not-a-real-token").statusCode(401);
		}

		refresh("not-a-real-token")
			.statusCode(429)
			.body("type", equalTo("urn:roomkeeper:problem:rate-limited"));
	}

	@Test
	void eachRouteHasItsOwnBucket() {
		for (int i = 1; i <= 20; i++) {
			postSignup(aUser().build());
		}
		postSignup(aUser().build()).statusCode(429);

		// Signup's limit doesn't touch login's
		TestUser user = aUser().build();
		api().contentType(ContentType.JSON).body(Map.of("email", user.email(), "password", user.password()))
			.when().post("/api/v1/auth/login")
			.then().statusCode(401);
	}

	private ValidatableResponse postSignup(TestUser user) {
		return api().contentType(ContentType.JSON).body(user.signupRequest())
			.when().post("/api/v1/auth/signup")
			.then();
	}

	private ValidatableResponse refresh(String token) {
		return api().contentType(ContentType.JSON).body(Map.of("refreshToken", token))
			.when().post("/api/v1/auth/refresh")
			.then();
	}

}
