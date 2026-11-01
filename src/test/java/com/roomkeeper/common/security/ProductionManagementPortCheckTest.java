package com.roomkeeper.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Actuator's paths are open in SecurityConfig because they're served on a port of their own
 * that deployments keep private. Served on the API's port they'd be public, metrics
 * included, so prod refuses to start that way.
 */
class ProductionManagementPortCheckTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(ProductionManagementPortCheck.class);

	@Test
	void startsWithActuatorOnAPortOfItsOwn() {
		runner.withPropertyValues("spring.profiles.active=prod", "server.port=8080", "management.server.port=8081")
			.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void refusesActuatorOnTheApiPort() {
		runner.withPropertyValues("spring.profiles.active=prod", "server.port=9000", "management.server.port=9000")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("MANAGEMENT_PORT"));
	}

	@Test
	void theApiPortDefaultsTo8080() {
		runner.withPropertyValues("spring.profiles.active=prod", "management.server.port=8080")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void refusesActuatorWithNoPortOfItsOwn() {
		runner.withPropertyValues("spring.profiles.active=prod", "server.port=8080")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void startsWithActuatorOff() {
		// -1 turns Actuator's HTTP endpoints off
		runner.withPropertyValues("spring.profiles.active=prod", "management.server.port=-1")
			.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void onlyProdIsChecked() {
		runner.withPropertyValues("management.server.port=8080").run(context -> assertThat(context).hasNotFailed());
	}

}
