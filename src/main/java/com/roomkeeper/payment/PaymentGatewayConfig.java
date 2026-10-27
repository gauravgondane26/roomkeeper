package com.roomkeeper.payment;

import java.util.concurrent.Executor;

import com.stripe.StripeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
class PaymentGatewayConfig {

	static final String CLOSING_EXECUTOR = "checkoutClosingExecutor";

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

	/**
	 * The thread that closes the checkout sessions of expired holds. One is plenty: few holds
	 * expire mid-payment, and a session left open closes on its own.
	 */
	@Bean(CLOSING_EXECUTOR)
	Executor checkoutClosingExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("checkout-closing-");
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(1_000);
		executor.setRejectedExecutionHandler(
				(task, pool) -> log.warn("Checkout closing queue full; a session will close on its own"));
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(10);
		return executor;
	}

}
