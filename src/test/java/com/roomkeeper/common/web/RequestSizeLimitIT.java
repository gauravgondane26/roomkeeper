package com.roomkeeper.common.web;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.hamcrest.Matchers.equalTo;

/**
 * Bodies over the limit are refused with 413 before anything buffers them: 64 KB for the
 * API, 1 MB for Stripe's webhook. A declared Content-Length is refused at once; a chunked
 * body (no declared length) is cut off once it passes the limit.
 */
class RequestSizeLimitIT extends AbstractIntegrationTest {

	private static final String TOO_LARGE = "urn:roomkeeper:problem:payload-too-large";

	@Test
	void anOversizedBodyIsRefusedBeforeLogin() {
		api().contentType(ContentType.JSON).body(loginWithEmailOf(100 * 1024))
			.when().post("/api/v1/auth/login")
			.then().statusCode(413).body("type", equalTo(TOO_LARGE));
	}

	@Test
	void aChunkedBodyIsCutOffOncePastTheLimit() {
		api().contentType(ContentType.JSON).body(new ByteArrayInputStream(loginWithEmailOf(100 * 1024)))
			.when().post("/api/v1/auth/login")
			.then().statusCode(413).body("type", equalTo(TOO_LARGE));
	}

	@Test
	void theWebhookMayBeLargerThanTheApiButNotUnlimited() {
		// Over the API's limit but under the webhook's: read, then refused for its signature
		api().contentType(ContentType.JSON).body(new byte[100 * 1024])
			.when().post("/webhook/payment")
			.then().statusCode(400).body("type", equalTo("urn:roomkeeper:problem:invalid-webhook-signature"));

		api().contentType(ContentType.JSON).body(new byte[2 * 1024 * 1024])
			.when().post("/webhook/payment")
			.then().statusCode(413).body("type", equalTo(TOO_LARGE));
	}

	@Test
	void aRouteThatBuffersItsBodyStillAnswers413() {
		String guest = signUpAndLogIn(aUser().build());

		// The idempotency filter reads the whole body before the controller does
		idempotentApi(guest).contentType(ContentType.JSON).body(new ByteArrayInputStream(new byte[100 * 1024]))
			.when().post("/api/v1/bookings/init")
			.then().statusCode(413).body("type", equalTo(TOO_LARGE));
	}

	@Test
	void anOrdinaryRequestIsUnaffected() {
		api().contentType(ContentType.JSON).body(loginWithEmailOf(20))
			.when().post("/api/v1/auth/login")
			.then().statusCode(401);
	}

	/** A login body whose email is {@code length} characters long. */
	private static byte[] loginWithEmailOf(int length) {
		return ("{\"email\":\"" + "a".repeat(length) + "\",\"password\":\"x\"}").getBytes(StandardCharsets.UTF_8);
	}

}
