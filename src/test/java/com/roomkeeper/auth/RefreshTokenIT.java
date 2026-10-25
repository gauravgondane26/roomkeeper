package com.roomkeeper.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.TestUser;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

class RefreshTokenIT extends AbstractIntegrationTest {

	@Autowired
	StringRedisTemplate redis;

	@Test
	void loginIncludesARefreshTokenValidForSevenDays() {
		TestUser user = aUser().build();
		signUp(user);

		login(user)
			.body("refreshToken", notNullValue())
			.body("refreshExpiresIn", equalTo(7 * 24 * 60 * 60));
	}

	@Test
	void refreshReturnsANewTokenPairThatWorks() {
		TestUser user = aUser().build();
		signUp(user);
		String refreshToken = login(user).extract().path("refreshToken");

		JsonPath refreshed = refresh(refreshToken)
			.statusCode(200)
			.body("tokenType", equalTo("Bearer"))
			.body("refreshToken", not(equalTo(refreshToken)))
			.extract().jsonPath();

		api(refreshed.getString("accessToken"))
			.when().get("/api/v1/users/profile")
			.then().statusCode(200).body("email", equalTo(user.email()));
	}

	@Test
	void aRefreshTokenWorksOnlyOnce() {
		TestUser user = aUser().build();
		signUp(user);
		String refreshToken = login(user).extract().path("refreshToken");
		refresh(refreshToken).statusCode(200);

		refresh(refreshToken)
			.statusCode(401)
			.body("type", equalTo("urn:roomkeeper:problem:invalid-refresh-token"));
	}

	@Test
	void concurrentRefreshesWithTheSameTokenSucceedOnlyOnce() throws Exception {
		TestUser user = aUser().build();
		signUp(user);
		String refreshToken = login(user).extract().path("refreshToken");

		int threads = 10;
		var ready = new CountDownLatch(threads);
		var go = new CountDownLatch(1);
		List<Integer> statuses = new ArrayList<>();
		try (var pool = Executors.newFixedThreadPool(threads)) {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				Callable<Integer> attempt = () -> {
					ready.countDown();
					go.await();
					return refresh(refreshToken).extract().statusCode();
				};
				results.add(pool.submit(attempt));
			}
			ready.await();
			go.countDown();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}
		}

		assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
		assertThat(statuses).filteredOn(status -> status == 401).hasSize(threads - 1);
	}

	@Test
	void logoutRevokesTheRefreshToken() {
		TestUser user = aUser().build();
		signUp(user);
		String refreshToken = login(user).extract().path("refreshToken");

		logout(refreshToken).statusCode(204);

		refresh(refreshToken)
			.statusCode(401)
			.body("type", equalTo("urn:roomkeeper:problem:invalid-refresh-token"));
	}

	@Test
	void logoutWithAnUnknownTokenStillSucceeds() {
		logout("not-a-real-token").statusCode(204);
	}

	@Test
	void unknownRefreshTokenIsRejected() {
		refresh("not-a-real-token")
			.statusCode(401)
			.body("type", equalTo("urn:roomkeeper:problem:invalid-refresh-token"));
	}

	@Test
	void redisStoresOnlyAHashOfTheTokenWithASevenDayExpiry() throws Exception {
		TestUser user = aUser().build();
		long userId = signUp(user);
		String refreshToken = login(user).extract().path("refreshToken");

		String hashedKey = "refresh:" + HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.US_ASCII)));
		assertThat(redis.hasKey("refresh:" + refreshToken)).isFalse();
		assertThat(redis.opsForValue().get(hashedKey)).isEqualTo(String.valueOf(userId));
		assertThat(redis.getExpire(hashedKey)).isBetween(Duration.ofDays(7).minusMinutes(1).toSeconds(),
				Duration.ofDays(7).toSeconds());
	}

	@Test
	void missingRefreshTokenIsAValidationProblem() {
		api().contentType(ContentType.JSON).body(Map.of())
			.when().post("/api/v1/auth/refresh")
			.then()
				.statusCode(400)
				.body("type", equalTo("urn:roomkeeper:problem:validation"));
	}

	private ValidatableResponse login(TestUser user) {
		return api().contentType(ContentType.JSON).body(Map.of("email", user.email(), "password", user.password()))
			.when().post("/api/v1/auth/login")
			.then().statusCode(200);
	}

	private ValidatableResponse refresh(String refreshToken) {
		return api().contentType(ContentType.JSON).body(Map.of("refreshToken", refreshToken))
			.when().post("/api/v1/auth/refresh")
			.then();
	}

	private ValidatableResponse logout(String refreshToken) {
		return api().contentType(ContentType.JSON).body(Map.of("refreshToken", refreshToken))
			.when().post("/api/v1/auth/logout")
			.then();
	}

}
