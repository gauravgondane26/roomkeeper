package com.roomkeeper.common.ratelimit;

import java.time.Instant;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Rate limits count per client IP. Behind a proxy listed in TRUSTED_PROXIES that's the
 * address the proxy forwarded in X-Forwarded-For; from anyone else the header is ignored,
 * or every client could claim a fresh address for each request and never be limited. Login
 * (10 attempts a minute) shows it; the clock stands still so the bucket doesn't refill.
 */
@Import(MutableClockConfig.class)
class ForwardedClientIpIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	@BeforeEach
	void stopTheClock() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
	}

	@Test
	void withNoTrustedProxyXForwardedForIsIgnored() {
		for (int i = 0; i < 10; i++) {
			login("198.51.100." + i).statusCode(401);
		}

		login("198.51.100.99").statusCode(429);
	}

	/** The tests' requests come from localhost, which plays the trusted proxy here. */
	@Nested
	@Import(MutableClockConfig.class)
	@TestPropertySource(properties = "TRUSTED_PROXIES=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1")
	class BehindATrustedProxy extends AbstractIntegrationTest {

		@Autowired
		MutableClock clock;

		@BeforeEach
		void stopTheClock() {
			clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		}

		@Test
		void eachForwardedClientHasItsOwnBucket() {
			for (int i = 0; i < 10; i++) {
				login("198.51.100.1").statusCode(401);
			}

			login("198.51.100.1").statusCode(429);
			login("198.51.100.2").statusCode(401);
		}

		@Test
		void addressesAClientPutsInFrontOfWhatTheProxyAddedDontCount() {
			for (int i = 0; i < 10; i++) {
				login("198.51.100.1").statusCode(401);
			}

			// The proxy appends the address it saw; whatever the client sent before that is ignored
			login("203.0.113.5, 198.51.100.1").statusCode(429);
		}

		private ValidatableResponse login(String forwardedFor) {
			return api().header("X-Forwarded-For", forwardedFor).contentType(ContentType.JSON)
				.body(Map.of("email", "nobody@example.com", "password", "wrong-password"))
				.when().post("/api/v1/auth/login")
				.then();
		}

	}

	private ValidatableResponse login(String forwardedFor) {
		return api().header("X-Forwarded-For", forwardedFor).contentType(ContentType.JSON)
			.body(Map.of("email", "nobody@example.com", "password", "wrong-password"))
			.when().post("/api/v1/auth/login")
			.then();
	}

}
