package com.roomkeeper.payment;

import com.stripe.StripeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PaymentGatewayConfig {

	private static final Logger log = LoggerFactory.getLogger(PaymentGatewayConfig.class);

	/** Stripe when a secret key is set; otherwise payments answer 503 and everything else works. */
	@Bean
	PaymentGateway paymentGateway(PaymentProperties properties) {
		String secretKey = properties.stripe().secretKey();
		if (secretKey == null || secretKey.isBlank()) {
			log.warn("No Stripe secret key (STRIPE_SECRET_KEY): payments are off");
			return new UnconfiguredPaymentGateway();
		}
		StripeClient stripe = StripeClient.builder()
			.setApiKey(secretKey)
			// Fail fast rather than hold a request thread; the SDK retries safely with the idempotency key
			.setConnectTimeout(5_000)
			.setReadTimeout(20_000)
			.setMaxNetworkRetries(2)
			.build();
		return new StripePaymentGateway(stripe, properties);
	}

}
