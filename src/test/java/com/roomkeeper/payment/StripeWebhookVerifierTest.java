package com.roomkeeper.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static com.roomkeeper.testsupport.StripeWebhooks.TEST_SECRET;
import static com.roomkeeper.testsupport.StripeWebhooks.checkoutCompleted;
import static com.roomkeeper.testsupport.StripeWebhooks.signature;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeWebhookVerifierTest {

	private static final Instant NOW = Instant.parse("2026-03-10T09:00:00Z");

	private static final String PAID = checkoutCompleted("evt_1", "cs_test_1", "pi_test_1");

	private final StripeWebhookVerifier verifier = verifier(TEST_SECRET);

	@Test
	void readsAnEventStripeSigned() {
		PaymentEvent event = verifier.verify(PAID, signature(PAID, NOW));

		assertThat(event).isEqualTo(new PaymentEvent("evt_1", "checkout.session.completed", "cs_test_1", "paid",
				"pi_test_1"));
		assertThat(event.paid()).isTrue();
	}

	@Test
	void refusesAPayloadChangedAfterSigning() {
		String forged = PAID.replace("pi_test_1", "pi_test_2");

		assertInvalid(() -> verifier.verify(forged, signature(PAID, NOW)));
	}

	@Test
	void refusesASignatureMadeWithAnotherSecret() {
		assertInvalid(() -> verifier.verify(PAID, signature(PAID, NOW, "whsec_someone_else")));
	}

	@Test
	void refusesAnOldDeliveryReplayed() {
		// Stripe's tolerance: five minutes
		assertThat(verifier.verify(PAID, signature(PAID, NOW.minus(Duration.ofMinutes(4))))).isNotNull();

		assertInvalid(() -> verifier.verify(PAID, signature(PAID, NOW.minus(Duration.ofMinutes(6)))));
	}

	@Test
	void refusesAnUnsignedDelivery() {
		assertInvalid(() -> verifier.verify(PAID, null));
		assertInvalid(() -> verifier.verify(PAID, "not a signature"));
	}

	@Test
	void withoutAWebhookSecretWebhooksAnswer503() {
		assertThatThrownBy(() -> verifier("").verify(PAID, signature(PAID, NOW)))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.type()).isEqualTo(ProblemType.PAYMENTS_UNAVAILABLE));
	}

	private static StripeWebhookVerifier verifier(String secret) {
		PaymentProperties properties = new PaymentProperties("inr", "https://roomkeeper.test/complete",
				"https://roomkeeper.test/cancelled", new PaymentProperties.Stripe("sk_test_x", secret));
		return new StripeWebhookVerifier(properties, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable verify) {
		assertThatThrownBy(verify).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemType.INVALID_WEBHOOK_SIGNATURE));
	}

}
