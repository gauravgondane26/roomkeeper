package com.roomkeeper.auth;

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
import static org.hamcrest.Matchers.notNullValue;

/**
 * Failed logins are limited per account as well as per IP: after 5 wrong passwords within
 * 15 minutes, logins to that email are refused with 429 whichever address they come from,
 * the right password included. Unknown emails are treated the same, so the limit doesn't
 * reveal which accounts exist. Successful logins don't count.
 */
@Import(MutableClockConfig.class)
class LoginAccountLimitIT extends AbstractIntegrationTest {

	private static final String RATE_LIMITED = "urn:roomkeeper:problem:rate-limited";

	@Autowired
	MutableClock clock;

	@Test
	void fiveWrongPasswordsLockTheAccountForAWhileEvenForTheRightOne() {
		TestUser user = aUser().build();
		signUp(user);

		for (int attempt = 1; attempt <= 5; attempt++) {
			login(user.email(), "wrong-password-" + attempt).statusCode(401);
		}

		login(user.email(), user.password()).statusCode(429)
			.header("Retry-After", notNullValue())
			.body("type", equalTo(RATE_LIMITED));
	}

	@Test
	void theLimitPassesWithTime() {
		TestUser user = aUser().build();
		signUp(user);
		for (int attempt = 1; attempt <= 5; attempt++) {
			login(user.email(), "wrong-password-" + attempt).statusCode(401);
		}

		clock.advance(Duration.ofMinutes(15));

		login(user.email(), user.password()).statusCode(200);
	}

	@Test
	void otherAccountsAreUnaffected() {
		TestUser locked = aUser().build();
		TestUser other = aUser().build();
		signUp(locked);
		signUp(other);
		for (int attempt = 1; attempt <= 5; attempt++) {
			login(locked.email(), "wrong-password-" + attempt).statusCode(401);
		}

		login(other.email(), other.password()).statusCode(200);
	}

	@Test
	void anUnknownEmailIsLimitedTheSameWay() {
		String nobody = "nobody-" + System.nanoTime() + "@example.com";
		for (int attempt = 1; attempt <= 5; attempt++) {
			login(nobody, "wrong-password-" + attempt).statusCode(401);
		}

		login(nobody, "wrong-password-6").statusCode(429).body("type", equalTo(RATE_LIMITED));
	}

	@Test
	void successfulLoginsDontCount() {
		TestUser user = aUser().build();
		signUp(user);

		for (int attempt = 1; attempt <= 6; attempt++) {
			login(user.email(), user.password()).statusCode(200);
		}
	}

	@Test
	void theEmailIsMatchedAsLoginReadsIt() {
		TestUser user = aUser().build();
		signUp(user);
		for (int attempt = 1; attempt <= 5; attempt++) {
			login(" " + user.email().toUpperCase() + " ", "wrong-password-" + attempt).statusCode(401);
		}

		login(user.email(), user.password()).statusCode(429);
	}

	private ValidatableResponse login(String email, String password) {
		return api().contentType(ContentType.JSON).body(Map.of("email", email, "password", password))
			.when().post("/api/v1/auth/login")
			.then();
	}

}
