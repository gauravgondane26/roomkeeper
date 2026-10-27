package com.roomkeeper.payment;

import java.math.BigDecimal;

/** A payment attempt: the guest pays at {@code checkoutUrl}. */
public record PaymentResponse(long id, long bookingId, PaymentStatus status, BigDecimal amount, String currency,
		String checkoutUrl) {
}
