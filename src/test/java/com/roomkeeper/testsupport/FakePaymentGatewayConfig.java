package com.roomkeeper.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Every integration test pays through {@link FakePaymentGateway}, never Stripe. */
@TestConfiguration(proxyBeanMethods = false)
public class FakePaymentGatewayConfig {

	@Bean
	@Primary
	FakePaymentGateway fakePaymentGateway() {
		return new FakePaymentGateway();
	}

}
