package com.roomkeeper.payment;

import java.math.BigDecimal;
import java.time.Instant;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentGatewayConfigTest {

	@Test
	void usesStripeWhenASecretKeyIsSet() {
		assertThat(new PaymentGatewayConfig().paymentGateway(properties("sk_test_x")))
			.isInstanceOf(StripePaymentGateway.class);
	}

	@Test
	void withoutASecretKeyPaymentsAnswer503() {
		for (String missing : new String[] { null, "", "  " }) {
			PaymentGateway gateway = new PaymentGatewayConfig().paymentGateway(properties(missing));

			assertThatThrownBy(() -> gateway.createCheckoutSession(new CheckoutRequest(7, new BigDecimal("8000.00"),
					"Deluxe", Instant.parse("2026-03-10T09:30:00Z"), "key")))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.type()).isEqualTo(ProblemType.PAYMENTS_UNAVAILABLE));
		}
	}

	private static PaymentProperties properties(String secretKey) {
		return new PaymentProperties("inr", "https://roomkeeper.test/complete", "https://roomkeeper.test/cancelled",
				new PaymentProperties.Stripe(secretKey, "whsec_x"));
	}

}
