package com.roomkeeper.common.error;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

class ProblemDetailsIT extends AbstractIntegrationTest {

	@Test
	void anonymousRequestToAProtectedRouteIsAnUnauthorizedProblem() {
		// Includes routes that don't exist: anonymous callers can't probe which routes exist
		api()
			.when().get("/api/v1/does-not-exist")
			.then()
				.statusCode(401)
				.contentType(startsWith("application/problem+json"))
				.body("type", equalTo("urn:roomkeeper:problem:unauthorized"))
				.body("instance", equalTo("/api/v1/does-not-exist"));
	}

}
