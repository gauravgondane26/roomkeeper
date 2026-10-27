package com.roomkeeper.payment;

/**
 * The parts of a verified Stripe event that payments use: for checkout session events, the
 * session, whether it's paid, and the payment intent refunds go against.
 */
public record PaymentEvent(String id, String type, String sessionId, String paymentStatus, String paymentIntentId) {

	public boolean paid() {
		return "paid".equals(paymentStatus);
	}

}
