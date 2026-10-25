package com.roomkeeper.common.error;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

class ProblemDetailsIT extends AbstractIntegrationTest {

	@Test
	void unknownRouteIsANotFoundProblem() {
		api()
			.when().get("/api/v1/does-not-exist")
			.then()
				.statusCode(404)
				.contentType(startsWith("application/problem+json"))
				.body("type", equalTo("urn:roomkeeper:problem:not-found"))
				.body("instance", equalTo("/api/v1/does-not-exist"))
				.body("detail", equalTo("No endpoint GET /api/v1/does-not-exist"));
	}

}
