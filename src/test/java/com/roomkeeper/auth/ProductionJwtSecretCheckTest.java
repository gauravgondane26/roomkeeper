package com.roomkeeper.auth;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** In prod the app won't start with a JWT secret that's committed to this repository. */
class ProductionJwtSecretCheckTest {

	private static final String LOCAL_SECRET = "VrafQC2/RfRzmNJ2WUp9ketFZXrRb41ZQn0ZXAJEyCw=";

	private static final String TEST_SECRET = "Ppkmwuhp1b/rqHAnF96nUxXtny03ZgOBUh3NbcEk7yk=";

	@Test
	void prodRefusesTheLocalAndTestProfilesSecrets() {
		for (String secret : new String[] { LOCAL_SECRET, TEST_SECRET, " " + LOCAL_SECRET + "\n" }) {
			runner("prod", secret).run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("JWT_SECRET")
				.hasMessageNotContaining(secret.strip()));
		}
	}

	@Test
	void prodStartsWithASecretOfItsOwn() {
		runner("prod", "q0bU8M4eYqOa7TqH1Z7oKZ0q9V1bS0r3Q5sB2nX8w1c=")
			.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ProductionJwtSecretCheck.class));
	}

	@Test
	void otherProfilesArentChecked() {
		runner("local", LOCAL_SECRET)
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ProductionJwtSecretCheck.class));
	}

	private static ApplicationContextRunner runner(String profile, String secret) {
		return new ApplicationContextRunner()
			.withPropertyValues("spring.profiles.active=" + profile)
			.withBean(AuthProperties.class,
					() -> new AuthProperties(secret, "roomkeeper", Duration.ofMinutes(15), Duration.ofDays(7)))
			.withUserConfiguration(ProductionJwtSecretCheck.class);
	}

}
