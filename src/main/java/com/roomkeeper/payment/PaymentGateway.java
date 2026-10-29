package com.roomkeeper.payment;

import java.math.BigDecimal;

/**
 * The payment provider (Stripe), behind an interface so tests use a fake. Each call either
 * succeeds or throws an {@code ApiException} of type {@code payments-unavailable}.
 */
public interface PaymentGateway {

	/** Opens a hosted checkout page for the amount. The same idempotency key returns the same session. */
	CheckoutSession createCheckoutSession(CheckoutRequest request);

	/** Closes an open checkout session, so it can't be paid any more. */
	void expireCheckoutSession(String sessionId);

	/**
	 * Refunds {@code amount} of a payment and returns the refund's id. The same idempotency
	 * key never refunds twice.
	 */
	String refund(String paymentIntentId, BigDecimal amount, String idempotencyKey);

}
