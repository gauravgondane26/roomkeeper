package com.roomkeeper.payment;

import java.math.BigDecimal;
import java.time.Instant;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.stripe.StripeClient;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripePaymentGatewayTest {

	private static final PaymentProperties PROPERTIES = new PaymentProperties("inr",
			"https://roomkeeper.test/payments/complete.html?session_id={CHECKOUT_SESSION_ID}",
			"https://roomkeeper.test/payments/cancelled.html", new PaymentProperties.Stripe("sk_test_x", "whsec_x"));

	private static final CheckoutRequest REQUEST = new CheckoutRequest(7, new BigDecimal("8000.00"),
			"Deluxe at Seaside Inn, 2 nights", Instant.parse("2026-03-10T09:30:00Z"), "booking-7-payment-1");

	@Test
	void chargesTheFrozenAmountInPaise() {
		SessionCreateParams params = StripePaymentGateway.checkoutParams(REQUEST, PROPERTIES);

		assertThat(params.getMode()).isEqualTo(SessionCreateParams.Mode.PAYMENT);
		assertThat(params.getLineItems()).singleElement().satisfies(item -> {
			assertThat(item.getQuantity()).isEqualTo(1L);
			assertThat(item.getPriceData().getCurrency()).isEqualTo("inr");
			assertThat(item.getPriceData().getUnitAmount()).isEqualTo(800_000L);
			assertThat(item.getPriceData().getProductData().getName()).isEqualTo("Deluxe at Seaside Inn, 2 nights");
		});
	}

	@Test
	void tiesTheSessionAndItsPaymentToTheBooking() {
		SessionCreateParams params = StripePaymentGateway.checkoutParams(REQUEST, PROPERTIES);

		assertThat(params.getClientReferenceId()).isEqualTo("7");
		assertThat(params.getMetadata()).containsEntry("booking_id", "7");
		assertThat(params.getPaymentIntentData().getMetadata()).containsEntry("booking_id", "7");
	}

	@Test
	void stopsTakingPaymentAtTheGivenTimeAndSendsTheGuestBack() {
		SessionCreateParams params = StripePaymentGateway.checkoutParams(REQUEST, PROPERTIES);

		assertThat(params.getExpiresAt()).isEqualTo(Instant.parse("2026-03-10T09:30:00Z").getEpochSecond());
		assertThat(params.getSuccessUrl())
			.isEqualTo("https://roomkeeper.test/payments/complete.html?session_id={CHECKOUT_SESSION_ID}");
		assertThat(params.getCancelUrl()).isEqualTo("https://roomkeeper.test/payments/cancelled.html");
	}

	@Test
	void refusesAmountsFinerThanAPaisa() {
		assertThat(StripePaymentGateway.minorUnits(new BigDecimal("0.01"))).isEqualTo(1L);

		assertThatThrownBy(() -> StripePaymentGateway.minorUnits(new BigDecimal("10.005")))
			.isInstanceOf(ArithmeticException.class);
	}

	@Test
	void stripeBeingUnreachableIsA503() {
		// Nothing listens on port 9: the connection is refused straight away
		StripeClient unreachable = StripeClient.builder()
			.setApiKey("sk_test_x")
			.setApiBase("http://127.0.0.1:9")
			.setMaxNetworkRetries(0)
			.build();
		var gateway = new StripePaymentGateway(unreachable, PROPERTIES);

		assertThatThrownBy(() -> gateway.createCheckoutSession(REQUEST))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.type()).isEqualTo(ProblemType.PAYMENTS_UNAVAILABLE));
		assertThatThrownBy(() -> gateway.expireCheckoutSession("cs_test_1")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> gateway.refund("pi_test_1", "refund-1")).isInstanceOf(ApiException.class);
	}

}
