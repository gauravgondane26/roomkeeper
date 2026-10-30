package com.roomkeeper.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.RaceTest;
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
	void reusingARotatedTokenRevokesTheWholeSessionIncludingTheNewestToken() {
		TestUser user = aUser().build();
		signUp(user);
		String stolen = login(user).extract().path("refreshToken");
		String newest = refresh(stolen).statusCode(200).extract().path("refreshToken");

		refresh(stolen)
			.statusCode(401)
			.body("type", equalTo("urn:roomkeeper:problem:invalid-refresh-token"));

		refresh(newest)
			.statusCode(401)
			.body("type", equalTo("urn:roomkeeper:problem:invalid-refresh-token"));
	}

	@Test
	void reuseRevokesOnlyThatSessionNotTheUsersOthers() {
		TestUser user = aUser().build();
		signUp(user);
		String phone = login(user).extract().path("refreshToken");
		String laptop = login(user).extract().path("refreshToken");
		refresh(phone).statusCode(200);

		refresh(phone).statusCode(401);

		refresh(laptop).statusCode(200);
	}

	@Test
	void logoutWithAnAlreadyRotatedTokenStillEndsTheSession() {
		TestUser user = aUser().build();
		signUp(user);
		String first = login(user).extract().path("refreshToken");
		String newest = refresh(first).statusCode(200).extract().path("refreshToken");

		logout(first).statusCode(204);

		refresh(newest).statusCode(401);
	}

	@RaceTest
	void concurrentRefreshesWithTheSameTokenSucceedOnlyOnce() throws Exception {
		TestUser user = aUser().build();
		signUp(user);
		String refreshToken = login(user).extract().path("refreshToken");

		int threads = 10;
		var ready = new CountDownLatch(threads);
		var go = new CountDownLatch(1);
		List<Integer> statuses = new ArrayList<>();
		var issued = new ConcurrentLinkedQueue<String>();
		try (var pool = Executors.newFixedThreadPool(threads)) {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				Callable<Integer> attempt = () -> {
					ready.countDown();
					go.await();
					var response = refresh(refreshToken).extract();
					if (response.statusCode() == 200) {
						issued.add(response.path("refreshToken"));
					}
					return response.statusCode();
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
		// The losers presented a token already used, which can't be told apart from theft,
		// so the session ends and the winner's new token is refused too
		refresh(issued.remove()).statusCode(401);
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

		String hash = HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.US_ASCII)));
		String tokenKey = "refresh:" + hash;
		assertThat(redis.hasKey("refresh:" + refreshToken)).isFalse();
		String[] owner = redis.opsForValue().get(tokenKey).split(":");
		assertThat(owner[0]).isEqualTo(String.valueOf(userId));
		String familyKey = "refresh-family:" + owner[1];
		assertThat(redis.opsForValue().get(familyKey)).isEqualTo(hash);
		for (String key : List.of(tokenKey, familyKey)) {
			assertThat(redis.getExpire(key)).isBetween(Duration.ofDays(7).minusMinutes(1).toSeconds(),
					Duration.ofDays(7).toSeconds());
		}
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
