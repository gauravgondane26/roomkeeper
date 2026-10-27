package com.roomkeeper.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Payments through Stripe Checkout, in test mode. Amounts are charged in {@code currency}
 * (lowercase ISO code, a currency with two decimal places). After paying or giving up, Stripe
 * sends the guest to {@code successUrl} or {@code cancelUrl}; only the webhook confirms the
 * booking. Without a Stripe secret key, payments are off.
 */
@ConfigurationProperties("roomkeeper.payments")
public record PaymentProperties(String currency, String successUrl, String cancelUrl, Stripe stripe) {

	public record Stripe(String secretKey, String webhookSecret) {
	}

}
