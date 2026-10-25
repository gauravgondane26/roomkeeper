package com.roomkeeper;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

class HealthEndpointIT extends AbstractIntegrationTest {

	@Test
	void reportsUpOverHttp() {
		api()
			.when().get("/actuator/health")
			.then()
				.statusCode(200)
				.body("status", equalTo("UP"));
	}

}
