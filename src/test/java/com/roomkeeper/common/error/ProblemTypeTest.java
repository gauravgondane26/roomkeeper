package com.roomkeeper.common.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemTypeTest {

	@Test
	void frameworkErrorsGetTheGeneralTypeForTheirStatus() {
		assertThat(ProblemType.forStatus(400)).contains(ProblemType.BAD_REQUEST);
		assertThat(ProblemType.forStatus(405)).contains(ProblemType.METHOD_NOT_ALLOWED);
		assertThat(ProblemType.forStatus(409)).contains(ProblemType.CONFLICT);
		assertThat(ProblemType.forStatus(415)).contains(ProblemType.UNSUPPORTED_MEDIA_TYPE);
	}

	@Test
	void neverGivesAFrameworkErrorASpecificType() {
		// 422 only has a specific type (a reused idempotency key), which would mislabel it
		assertThat(ProblemType.forStatus(422)).isEmpty();
	}

}
