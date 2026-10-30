package com.roomkeeper.common.ratelimit;

import java.time.Instant;
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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Public search is limited to 300 requests a minute per client IP: plenty for a person, but
 * nobody can sweep it, or dodge the cache with a new query each time and load Postgres. Time
 * is held still with a MutableClock, so the bucket doesn't refill mid-test.
 */
@Import(MutableClockConfig.class)
class SearchRateLimitIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	@BeforeEach
	void stopTheClock() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
	}

	@Test
	void the301stSearchInAMinuteIsRejectedWith429() {
		for (int i = 0; i < 300; i++) {
			search(i).statusCode(200);
		}

		search(300).statusCode(429)
			.header("Retry-After", notNullValue())
			.body("type", equalTo("urn:roomkeeper:problem:rate-limited"));
		// Login keeps its own bucket
		api().contentType(ContentType.JSON).body(Map.of("email", "nobody@example.com", "password", "wrong-password"))
			.when().post("/api/v1/auth/login")
			.then().statusCode(401);
	}

	/** A different city each time, so every search misses the cache. */
	private ValidatableResponse search(int i) {
		return api().queryParam("city", "Nowhere-" + i).queryParam("checkIn", "2026-04-01")
			.queryParam("checkOut", "2026-04-02")
			.when().get("/api/v1/hotels/search")
			.then();
	}

}
