package com.roomkeeper.payment;

import java.time.Clock;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.common.security.SecurityEvents;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks that a webhook delivery really came from Stripe, then reads the event from the raw
 * payload. The signature is an HMAC of the timestamp and the exact payload with the
 * endpoint's secret; a delivery more than five minutes old is refused, so a captured one
 * can't be replayed later.
 * <p>
 * The event is read with Jackson rather than the SDK's typed objects, which come back empty
 * when the account's API version differs from the SDK's.
 */
@Component
class StripeWebhookVerifier {

	private static final long TOLERANCE_SECONDS = 300;

	private final PaymentProperties properties;

	private final JsonMapper json;

	private final Clock clock;

	private final SecurityEvents securityEvents;

	StripeWebhookVerifier(PaymentProperties properties, JsonMapper json, SecurityEvents securityEvents, Clock clock) {
		this.properties = properties;
		this.json = json;
		this.securityEvents = securityEvents;
		this.clock = clock;
	}

	PaymentEvent verify(String payload, String signatureHeader) {
		String secret = properties.stripe().webhookSecret();
		if (secret == null || secret.isBlank()) {
			throw new ApiException(ProblemType.PAYMENTS_UNAVAILABLE,
					"Webhooks aren't set up on this server (no Stripe webhook secret)");
		}
		if (signatureHeader == null) {
			throw invalid("Missing the Stripe-Signature header");
		}
		try {
			Webhook.Signature.verifyHeader(payload, signatureHeader, secret, TOLERANCE_SECONDS, clock);
		}
		catch (SignatureVerificationException ex) {
			throw invalid("The Stripe-Signature doesn't match this payload, or is too old");
		}
		try {
			StripeEvent event = json.readValue(payload, StripeEvent.class);
			CheckoutSessionObject session = event.data() == null || event.data().object() == null
					? new CheckoutSessionObject(null, null, null) : event.data().object();
			return new PaymentEvent(event.id(), event.type(), session.id(), session.paymentStatus(),
					session.paymentIntent());
		}
		catch (JacksonException ex) {
			throw invalid("The payload isn't a Stripe event");
		}
	}

	/** Refuses the delivery, and records it: forged or replayed deliveries are worth noticing. */
	private ApiException invalid(String detail) {
		securityEvents.webhookRejected(detail);
		return new ApiException(ProblemType.INVALID_WEBHOOK_SIGNATURE, detail);
	}

	private record StripeEvent(String id, String type, EventData data) {
	}

	private record EventData(CheckoutSessionObject object) {
	}

	/** The event's object; for other event types only {@code id} means anything. */
	private record CheckoutSessionObject(String id, @JsonProperty("payment_status") String paymentStatus,
			@JsonProperty("payment_intent") String paymentIntent) {
	}

}
