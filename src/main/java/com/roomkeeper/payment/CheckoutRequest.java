package com.roomkeeper.payment;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A hosted checkout page for one booking's amount, open until {@code expiresAt}. The same
 * idempotency key gets the same session back from the provider.
 */
public record CheckoutRequest(long bookingId, BigDecimal amount, String description, Instant expiresAt,
		String idempotencyKey) {
}
