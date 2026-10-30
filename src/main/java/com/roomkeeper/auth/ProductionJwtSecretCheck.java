package com.roomkeeper.auth;

import java.util.Set;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In prod, refuses to start with a JWT secret committed to this repository (the local and
 * test profiles' secrets, also Compose's old default): anyone who has read the repository
 * could sign their own access tokens with it, as any user or role.
 */
@Component
@Profile("prod")
class ProductionJwtSecretCheck implements InitializingBean {

	private static final Set<String> DEVELOPMENT_SECRETS = Set.of(
			// application-local.yaml
			"VrafQC2/RfRzmNJ2WUp9ketFZXrRb41ZQn0ZXAJEyCw=",
			// application-test.yaml
			"Ppkmwuhp1b/rqHAnF96nUxXtny03ZgOBUh3NbcEk7yk=");

	private final AuthProperties properties;

	ProductionJwtSecretCheck(AuthProperties properties) {
		this.properties = properties;
	}

	@Override
	public void afterPropertiesSet() {
		if (DEVELOPMENT_SECRETS.contains(properties.jwtSecret().strip())) {
			throw new IllegalStateException("JWT_SECRET is a development secret committed to the repository; set a "
					+ "secret of your own (openssl rand -base64 32, or scripts/init-env.sh for Docker Compose)");
		}
	}

}
