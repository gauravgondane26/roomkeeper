package com.roomkeeper.common.security;

import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.TestUser;
import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an attack looks like leaves a trace: failed logins, refused webhook deliveries and
 * denied access are logged (with no password, token or unknown email in the line) and
 * counted in roomkeeper_security_events_total by event, for a dashboard alert on a spike.
 */
@ExtendWith(OutputCaptureExtension.class)
class SecurityEventsIT extends AbstractIntegrationTest {

	@Autowired
	MeterRegistry meters;

	@Test
	void aWrongPasswordIsLoggedAndCountedWithoutThePassword(CapturedOutput output) {
		TestUser user = aUser().build();
		long userId = signUp(user);
		double before = events("login_failed");

		login(user.email(), "a-wrong-guess-to-find").statusCode(401);

		assertThat(events("login_failed") - before).isEqualTo(1);
		assertThat(output.getOut()).contains("Failed login for user " + userId).doesNotContain("a-wrong-guess-to-find");
	}

	@Test
	void aLoginForAnUnknownEmailDoesntLogTheEmail(CapturedOutput output) {
		String nobody = "nobody-" + System.nanoTime() + "@example.com";
		double before = events("login_failed");

		login(nobody, "whatever-password").statusCode(401);

		assertThat(events("login_failed") - before).isEqualTo(1);
		assertThat(output.getOut()).contains("Failed login for an email with no account").doesNotContain(nobody);
	}

	@Test
	void aForgedWebhookIsLoggedAndCounted(CapturedOutput output) {
		double before = events("webhook_rejected");

		api().header("Stripe-Signature", "t=1,v1=forged").contentType(ContentType.JSON).body("{}")
			.when().post("/webhook/payment")
			.then().statusCode(400);

		assertThat(events("webhook_rejected") - before).isEqualTo(1);
		assertThat(output.getOut()).contains("Refused a payment webhook delivery");
	}

	@Test
	void aGuestOnAnAdminRouteIsLoggedAndCounted(CapturedOutput output) {
		TestUser guest = aUser().build();
		long guestId = signUp(guest);
		String token = logIn(guest);
		double before = events("access_denied");

		api(token).when().get("/api/v1/admin/hotels").then().statusCode(403);

		assertThat(events("access_denied") - before).isEqualTo(1);
		assertThat(output.getOut()).contains("Access denied to user " + guestId + ": GET /api/v1/admin/hotels");
	}

	@Test
	void aManagerReachingForAnotherManagersHotelIsLoggedAndCounted(CapturedOutput output) {
		long hotelId = createHotel(signUpAndLogIn(aUser().asHotelManager().build()), aHotel().build());
		TestUser other = aUser().asHotelManager().build();
		long otherId = signUp(other);
		String token = logIn(other);
		double before = events("access_denied");

		api(token).when().get("/api/v1/admin/hotels/{id}", hotelId).then().statusCode(403);

		assertThat(events("access_denied") - before).isEqualTo(1);
		assertThat(output.getOut()).contains("Access denied to user " + otherId + ": GET /api/v1/admin/hotels/" + hotelId);
	}

	private double events(String event) {
		var counter = meters.find("roomkeeper.security.events").tag("event", event).counter();
		return counter == null ? 0 : counter.count();
	}

	private io.restassured.response.ValidatableResponse login(String email, String password) {
		return api().contentType(ContentType.JSON).body(Map.of("email", email, "password", password))
			.when().post("/api/v1/auth/login")
			.then();
	}

}
