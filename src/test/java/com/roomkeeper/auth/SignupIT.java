package com.roomkeeper.auth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import com.roomkeeper.outbox.Email;
import com.roomkeeper.outbox.FakeEmailSender;
import com.roomkeeper.outbox.OutboxPublisher;
import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.RaceTest;
import com.roomkeeper.testsupport.TestUser;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.Concurrently.runTogether;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * Signup gives the same answer whether the email is new or already registered, so it can't
 * be used to find out who has an account. A new account works at once and gets a welcome
 * email; the owner of a registered address is told someone tried to sign up with it.
 */
class SignupIT extends AbstractIntegrationTest {

	private static final String ACCEPTED = "Thanks. If this address can be used, you can log in now; we've emailed you.";

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	OutboxPublisher publisher;

	@Autowired
	FakeEmailSender emails;

	@Test
	void createsAGuestAndStoresOnlyAPasswordHash() {
		TestUser user = aUser().build();

		signUp(user.signupRequest())
			.statusCode(202)
			.body("message", equalTo(ACCEPTED))
			.body("$", not(hasKey("id")));

		Map<String, Object> row = jdbc.queryForMap("select name, role, password_hash from users where email = ?",
				user.email());
		assertThat(row).containsEntry("name", user.name()).containsEntry("role", "GUEST");
		assertThat((String) row.get("password_hash")).startsWith("{bcrypt}").doesNotContain(user.password());
		attemptLogin(user).statusCode(200);
	}

	@Test
	void canSignUpAsHotelManager() {
		TestUser manager = aUser().asHotelManager().build();

		signUp(manager.signupRequest()).statusCode(202);

		assertThat(jdbc.queryForObject("select role from users where email = ?", String.class, manager.email()))
			.isEqualTo("HOTEL_MANAGER");
	}

	@Test
	void storesEmailInLowerCase() {
		String mixedCase = "Mixed.Case-" + System.nanoTime() + "@Example.COM";

		signUp(aUser().withEmail(mixedCase).build().signupRequest()).statusCode(202);

		assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Long.class,
				mixedCase.toLowerCase()))
			.isOne();
	}

	@Test
	void aRegisteredEmailGetsExactlyTheSameAnswerAndChangesNothing() {
		TestUser owner = aUser().build();
		String first = signUp(owner.signupRequest()).statusCode(202).extract().asString();
		TestUser someoneElse = aUser().withEmail(owner.email().toUpperCase()).withPassword("another-password-1")
			.build();

		String second = signUp(someoneElse.signupRequest()).statusCode(202).extract().asString();

		assertThat(second).isEqualTo(first);
		assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Long.class, owner.email()))
			.isOne();
		attemptLogin(owner).statusCode(200);
		attemptLogin(someoneElse).statusCode(401);
	}

	@Test
	void aNewAccountIsWelcomedAndARegisteredAddressesOwnerIsTold() {
		TestUser owner = aUser().build();
		signUp(owner.signupRequest()).statusCode(202);
		signUp(owner.signupRequest()).statusCode(202);

		publisher.run();

		assertThat(emails.sentTo(owner.email())).extracting(Email::subject)
			.containsExactly("Welcome to RoomKeeper", "Someone tried to sign up with your email");
	}

	@RaceTest
	void racingSignupsWithOneEmailCreateOneAccountAndAnswerAlike() throws Exception {
		TestUser owner = aUser().build();
		List<Callable<Response>> signups = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			signups.add(() -> api().contentType(ContentType.JSON).body(owner.signupRequest())
				.when().post("/api/v1/auth/signup"));
		}

		List<Response> responses = runTogether(signups);

		assertThat(responses).extracting(Response::statusCode).containsOnly(202);
		assertThat(responses).extracting(Response::asString).containsOnly(responses.getFirst().asString());
		long userId = jdbc.queryForObject("select id from users where email = ?", Long.class, owner.email());
		assertThat(jdbc.queryForList("select type from outbox_events where aggregate_id = ? and type in "
				+ "('ACCOUNT_CREATED', 'SIGNUP_FOR_EXISTING_ACCOUNT') order by id", String.class, userId))
			.containsExactly("ACCOUNT_CREATED", "SIGNUP_FOR_EXISTING_ACCOUNT", "SIGNUP_FOR_EXISTING_ACCOUNT",
					"SIGNUP_FOR_EXISTING_ACCOUNT");
	}

	@Test
	void rejectsInvalidFieldsWithAValidationProblem() {
		signUp(Map.of("email", "not-an-email", "password", "short", "name", ""))
			.statusCode(400)
			.body("type", equalTo("urn:roomkeeper:problem:validation"))
			.body("errors.field", containsInAnyOrder("email", "password", "name"));
	}

	@Test
	void rejectsAPasswordOverBcryptsLimitOf72Bytes() {
		// 72 characters but 144 bytes: BCrypt can't hash it, so it must be a 400, not a 500
		String password = "é".repeat(72);

		signUp(aUser().withPassword(password).build().signupRequest())
			.statusCode(400)
			.body("type", equalTo("urn:roomkeeper:problem:validation"))
			.body("errors.field", contains("password"));
	}

	@Test
	void acceptsAPasswordOfExactly72Bytes() {
		// 36 two-byte characters
		TestUser user = aUser().withPassword("é".repeat(36)).build();

		signUp(user.signupRequest()).statusCode(202);

		attemptLogin(user).statusCode(200);
	}

	@Test
	void rejectsAnUnknownRole() {
		var body = new HashMap<>(aUser().build().signupRequest());
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

	private ValidatableResponse attemptLogin(TestUser user) {
		return api().contentType(ContentType.JSON).body(Map.of("email", user.email(), "password", user.password()))
			.when().post("/api/v1/auth/login")
			.then();
	}

}
