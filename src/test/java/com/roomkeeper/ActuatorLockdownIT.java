package com.roomkeeper;

import java.util.List;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * Actuator answers only on the management port, which a deployment doesn't publish: Compose
 * doesn't map it to the host, and Prometheus scrapes it over the Docker network. The public
 * port keeps the liveness and readiness probes that health checks need, and nothing else.
 */
class ActuatorLockdownIT extends AbstractIntegrationTest {

	@Value("${local.management.port}")
	private int managementPort;

	@Test
	void thePublicPortAnswersHealthProbesOnly() {
		api().when().get("/livez").then().statusCode(200).body("status", equalTo("UP"));
		api().when().get("/readyz").then().statusCode(200).body("status", equalTo("UP"));

		for (String path : List.of("/actuator", "/actuator/health", "/actuator/info", "/actuator/prometheus",
				"/actuator/metrics", "/actuator/env")) {
			api().when().get(path).then().statusCode(404);
		}
	}

	@Test
	void theManagementPortServesHealthAndPrometheus() {
		management().when().get("/actuator/health").then().statusCode(200).body("status", equalTo("UP"));
		management().when().get("/actuator/prometheus")
			.then().statusCode(200)
			.contentType(startsWith("text/plain"))
			.body(containsString("roomkeeper_bookings_initiated_total"));
		// Only health, info and prometheus are exposed, even there
		management().when().get("/actuator/env").then().statusCode(404);
		management().when().get("/actuator/metrics").then().statusCode(404);
	}

	private RequestSpecification management() {
		return given().port(managementPort);
	}

}
