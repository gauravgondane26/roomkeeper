package com.roomkeeper.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Access tokens are HS256 JWTs signed with one shared secret: the app both issues and
 * checks them, so there is no need for a key pair or a separate authorization server.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

	static final String ROLES_CLAIM = "roles";

	/** Allowance for clock differences between machines when checking exp and nbf. */
	private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

	@Bean
	SecretKey jwtSecretKey(AuthProperties properties) {
		return secretKey(properties.jwtSecret());
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
		return encoder(jwtSecretKey);
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSecretKey, AuthProperties properties, Clock clock) {
		return decoder(jwtSecretKey, properties.issuer(), clock);
	}

	/** Maps the roles claim to ROLE_* authorities, so hasRole("HOTEL_MANAGER") works. */
	@Bean
	JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName(ROLES_CLAIM);
		authorities.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return converter;
	}

	static SecretKey secretKey(String base64Secret) {
		byte[] bytes = Base64.getDecoder().decode(base64Secret);
		if (bytes.length < 32) {
			throw new IllegalArgumentException(
					"JWT secret must be at least 256 bits (32 bytes) for HS256, got " + bytes.length * 8 + " bits");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	static JwtEncoder encoder(SecretKey key) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(key));
	}

	static JwtDecoder decoder(SecretKey key, String issuer, Clock clock) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
		timestamps.setClock(clock);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(issuer)));
		return decoder;
	}

}
