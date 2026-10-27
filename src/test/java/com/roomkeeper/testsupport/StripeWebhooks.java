package com.roomkeeper.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stripe webhook deliveries, signed the way Stripe signs them: a {@code Stripe-Signature}
 * header of {@code t=<seconds>,v1=<HMAC-SHA256 of "t.payload" with the endpoint secret>}.
 */
public final class StripeWebhooks {

	/** The test profile's webhook secret (application-test.yaml). */
	public static final String TEST_SECRET = "whsec_roomkeeper_test";

	private StripeWebhooks() {
	}

	/** A checkout session paid in full. */
	public static String checkoutCompleted(String eventId, String sessionId, String paymentIntentId) {
		return checkoutEvent(eventId, "checkout.session.completed", sessionId, "paid", paymentIntentId);
	}

	public static String checkoutEvent(String eventId, String type, String sessionId, String paymentStatus,
			String paymentIntentId) {
		String intent = paymentIntentId == null ? "null" : "\"" + paymentIntentId + "\"";
		return """
				{"id":"%s","object":"event","type":"%s","data":{"object":{"id":"%s","object":"checkout.session",\
				"payment_status":"%s","payment_intent":%s}}}""".formatted(eventId, type, sessionId, paymentStatus, intent);
	}

	/** The header Stripe would send with the payload at {@code at}, signed with the test secret. */
	public static String signature(String payload, Instant at) {
		return signature(payload, at, TEST_SECRET);
	}

	public static String signature(String payload, Instant at, String secret) {
		try {
			Mac hmac = Mac.getInstance("HmacSHA256");
			hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			String signed = at.getEpochSecond() + "." + payload;
			return "t=" + at.getEpochSecond() + ",v1="
					+ HexFormat.of().formatHex(hmac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
