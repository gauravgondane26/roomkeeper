package com.roomkeeper.payment;

import java.math.BigDecimal;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;

/** Stands in when no Stripe secret key is set (local runs, CI): every payment answers 503. */
class UnconfiguredPaymentGateway implements PaymentGateway {

	@Override
	public CheckoutSession createCheckoutSession(CheckoutRequest request) {
		throw notConfigured();
	}

	@Override
	public void expireCheckoutSession(String sessionId) {
		throw notConfigured();
	}

	@Override
	public String refund(String paymentIntentId, BigDecimal amount, String idempotencyKey) {
		throw notConfigured();
	}

	private static ApiException notConfigured() {
		return new ApiException(ProblemType.PAYMENTS_UNAVAILABLE,
				"Payments aren't set up on this server (no Stripe secret key)");
	}

}
