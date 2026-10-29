package com.roomkeeper.outbox;

import java.math.BigDecimal;

/**
 * A cancellation made part or all of a payment due back. The amount was settled by the
 * cancellation policy when the guest cancelled, so every attempt refunds the same amount.
 */
public record RefundDue(long bookingId, long paymentId, String paymentIntentId, BigDecimal amount)
		implements OutboxPayload {

	@Override
	public OutboxEventType type() {
		return OutboxEventType.REFUND_DUE;
	}

	@Override
	public long aggregateId() {
		return bookingId;
	}

}
