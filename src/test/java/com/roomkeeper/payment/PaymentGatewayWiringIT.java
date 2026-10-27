package com.roomkeeper.payment;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.FakePaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentGatewayWiringIT extends AbstractIntegrationTest {

	@Autowired
	PaymentGateway gateway;

	@Test
	void integrationTestsNeverCallStripe() {
		assertThat(gateway).isInstanceOf(FakePaymentGateway.class);
	}

}
